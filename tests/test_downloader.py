import sys
import tempfile
import types
import unittest
from unittest.mock import patch
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app/src/main/python"))
from downloader import normalize_instagram_url, download_reel, DownloadCancelled


class DownloaderTests(unittest.TestCase):
    def test_strip_tracking(self):
        self.assertEqual(normalize_instagram_url(
            "https://www.instagram.com/reel/Dd4_34xme3A/?srtk=test"),
            "https://www.instagram.com/reel/Dd4_34xme3A/")

    def test_host_validation(self):
        with self.assertRaises(ValueError):
            normalize_instagram_url("https://instagram.com.attacker.test/reel/abc/")

    def test_path_validation(self):
        with self.assertRaises(ValueError):
            normalize_instagram_url("https://instagram.com/accounts/login/")

    def test_shortcode_validation(self):
        with self.assertRaises(ValueError):
            normalize_instagram_url("https://instagram.com/reel/@bad/")

    def test_mock_download_and_progress(self):
        class FakeYoutubeDL:
            def __init__(self, opts):
                self.opts = opts
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def extract_info(self, url, download):
                self.opts["progress_hooks"][0]({"status": "downloading", "downloaded_bytes": 1, "total_bytes": 2})
                self.opts["progress_hooks"][0]({"status": "finished"})
                Path(self.opts["outtmpl"].replace("%(id)s", "123").replace("%(ext)s", "mp4")).write_bytes(b"test")
        class Listener:
            def __init__(self):
                self.events = []
            def report(self, progress, message):
                self.events.append(progress)
        old = sys.modules.get("yt_dlp")
        sys.modules["yt_dlp"] = types.SimpleNamespace(YoutubeDL=FakeYoutubeDL)
        try:
            with tempfile.TemporaryDirectory() as d:
                listener = Listener()
                path = download_reel("https://www.instagram.com/reel/ABC/", d, listener)
                self.assertTrue(Path(path).is_file())
                self.assertTrue(listener.events)
        finally:
            if old is None:
                del sys.modules["yt_dlp"]
            else:
                sys.modules["yt_dlp"] = old

    def test_failed_download_removes_partial_files(self):
        class FakeYoutubeDL:
            def __init__(self, opts):
                self.opts = opts
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def extract_info(self, url, download):
                filename = self.opts["outtmpl"].replace("%(id)s", "123").replace("%(ext)s", "mp4")
                Path(filename + ".part").write_bytes(b"incomplete")
                raise OSError("simulated network failure")

        with patch.dict(sys.modules, {"yt_dlp": types.SimpleNamespace(YoutubeDL=FakeYoutubeDL)}):
            with tempfile.TemporaryDirectory() as d:
                with self.assertRaises(OSError):
                    download_reel("https://www.instagram.com/reel/ABC/", d)
                self.assertEqual(list(Path(d).iterdir()), [])

    def test_cancelled_download_removes_partial_files(self):
        class Listener:
            cancelled = False
            def report(self, percent, message):
                pass
            def isCancelled(self):
                return self.cancelled
        listener = Listener()

        class FakeYoutubeDL:
            def __init__(self, opts):
                self.opts = opts
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def extract_info(self, url, download):
                filename = self.opts["outtmpl"].replace("%(id)s", "123").replace("%(ext)s", "mp4")
                Path(filename + ".part").write_bytes(b"incomplete")
                listener.cancelled = True
                self.opts["progress_hooks"][0]({
                    "status": "downloading", "downloaded_bytes": 1, "total_bytes": 2
                })

        with patch.dict(sys.modules, {"yt_dlp": types.SimpleNamespace(YoutubeDL=FakeYoutubeDL)}):
            with tempfile.TemporaryDirectory() as d:
                with self.assertRaises(DownloadCancelled):
                    download_reel("https://www.instagram.com/reel/ABC/", d, listener)
                self.assertEqual(list(Path(d).iterdir()), [])

    def test_success_removes_sidecar_files(self):
        class FakeYoutubeDL:
            def __init__(self, opts):
                self.opts = opts
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def extract_info(self, url, download):
                filename = self.opts["outtmpl"].replace("%(id)s", "123").replace("%(ext)s", "mp4")
                Path(filename).write_bytes(b"video")
                Path(filename + ".part").write_bytes(b"partial")

        with patch.dict(sys.modules, {"yt_dlp": types.SimpleNamespace(YoutubeDL=FakeYoutubeDL)}):
            with tempfile.TemporaryDirectory() as d:
                result = Path(download_reel("https://www.instagram.com/reel/ABC/", d))
                self.assertTrue(result.is_file())
                self.assertEqual(list(Path(d).iterdir()), [result])


if __name__ == "__main__":
    unittest.main()
