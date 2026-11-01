"""Offline regression tests for supported YouTube Shorts links.

All examples are URL/mocked-extractor tests, NOT evidence of a real download.
"""
import json
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app/src/main/python"))

from downloader import (download_video, normalize_supported_url,
                        normalize_youtube_shorts_url, choose_shorts_formats)


SAMPLE_SHORTS = "https://m.youtube.com/shorts/xtcECX1rnuw"
NORMALIZED_SHORTS = "https://www.youtube.com/shorts/xtcECX1rnuw"


class ShortsUrlTests(unittest.TestCase):
    def test_user_provided_mobile_short(self):
        self.assertEqual(normalize_youtube_shorts_url(SAMPLE_SHORTS), NORMALIZED_SHORTS)
        self.assertEqual(normalize_supported_url(SAMPLE_SHORTS),
                         (NORMALIZED_SHORTS, "shorts"))

    def test_website_and_share_tracking(self):
        self.assertEqual(
            normalize_youtube_shorts_url(
                "https://www.youtube.com/shorts/xtcECX1rnuw?si=some_share_token"),
            NORMALIZED_SHORTS)
        self.assertEqual(normalize_youtube_shorts_url(
            "http://youtube.com/shorts/xtcECX1rnuw/"), NORMALIZED_SHORTS)

    def test_only_shorts_are_allowed(self):
        rejected = (
            "https://m.youtube.com/watch?v=xtcECX1rnuw",
            "https://youtube.com/playlist?list=PLtest",
            "https://youtu.be/xtcECX1rnuw",
            "https://youtube.com/@someone/shorts",
            "https://youtube.com/shorts/tooShort",
            "https://youtube.com/shorts/xtcECX1rnuw/extra",
            "https://youtube.com.evil.example/shorts/xtcECX1rnuw",
            "https://evil.example/shorts/xtcECX1rnuw",
            "https://user:pass@youtube.com/shorts/xtcECX1rnuw",
        )
        for url in rejected:
            with self.subTest(url=url), self.assertRaises(ValueError):
                normalize_supported_url(url)

    def test_instagram_unchanged(self):
        self.assertEqual(normalize_supported_url(
            "https://www.instagram.com/reel/ABC123/?igsh=track"),
            ("https://www.instagram.com/reel/ABC123/", "instagram"))

    def test_prefers_muxed_file(self):
        formats = [
            {"format_id": "137", "ext": "mp4", "vcodec": "avc1.640028",
             "acodec": "none", "url": "https://example.test/video"},
            {"format_id": "140", "ext": "m4a", "vcodec": "none",
             "acodec": "mp4a.40.2", "url": "https://example.test/audio"},
            {"format_id": "18", "ext": "mp4", "vcodec": "avc1.42001E",
             "acodec": "mp4a.40.2", "url": "https://example.test/both"},
        ]
        self.assertEqual(choose_shorts_formats(formats), ("combined", "18", None))

    def test_falls_back_to_avc_and_aac_tracks(self):
        formats = [
            {"format_id": "137", "ext": "mp4", "vcodec": "avc1.640028",
             "acodec": "none", "height": 1080, "url": "https://example.test/video"},
            {"format_id": "136", "ext": "mp4", "vcodec": "avc1.4d401f",
             "acodec": "none", "height": 720, "url": "https://example.test/video2"},
            {"format_id": "140", "ext": "m4a", "vcodec": "none",
             "acodec": "mp4a.40.2", "url": "https://example.test/audio"},
        ]
        self.assertEqual(choose_shorts_formats(formats), ("split", "137", "140"))

    def test_does_not_claim_unsupported_formats_are_playable(self):
        formats = [
            {"format_id": "248", "ext": "webm", "vcodec": "vp9", "acodec": "none",
             "url": "https://example.test/video"},
            {"format_id": "251", "ext": "webm", "vcodec": "none", "acodec": "opus",
             "url": "https://example.test/audio"},
        ]
        with self.assertRaisesRegex(RuntimeError, "external JS runtime"):
            choose_shorts_formats(formats)

    def test_mock_shorts_download_selects_combined_media(self):
        seen = []
        class FakeYoutubeDL:
            def __init__(self, options):
                self.options = options
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def extract_info(self, url, download, process=True):
                seen.append((url, download, self.options.get("format"), process))
                if not download:
                    return {"formats": [
                        {"format_id": "18", "ext": "mp4", "vcodec": "avc1.42001E",
                         "acodec": "mp4a.40.2", "url": "https://example.test/both"}]}
                filename = self.options["outtmpl"].replace("%(ext)s", "mp4")
                Path(filename).write_bytes(b"mock video with audio")
        with patch.dict(sys.modules, {"yt_dlp": types.SimpleNamespace(YoutubeDL=FakeYoutubeDL)}):
            with tempfile.TemporaryDirectory() as directory:
                path = Path(download_video(SAMPLE_SHORTS, directory))
                self.assertTrue(path.is_file())
                self.assertTrue(path.name.startswith("shorts_"))
        self.assertEqual(seen[0], (NORMALIZED_SHORTS, False, None, False))
        self.assertEqual(seen[1], (NORMALIZED_SHORTS, True, "18", True))

    def test_mock_split_download_returns_tracks_and_cleans_sidecars(self):
        seen = []
        class FakeYoutubeDL:
            def __init__(self, options):
                self.options = options
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def extract_info(self, url, download, process=True):
                if not download:
                    return {"formats": [
                        {"format_id": "137", "ext": "mp4", "vcodec": "avc1.640028",
                         "acodec": "none", "height": 1080, "url": "https://example.test/video"},
                        {"format_id": "140", "ext": "m4a", "vcodec": "none",
                         "acodec": "mp4a.40.2", "url": "https://example.test/audio"}]}
                fmt = self.options["format"]
                seen.append(fmt)
                ext = "mp4" if fmt == "137" else "m4a"
                filename = self.options["outtmpl"].replace("%(ext)s", ext)
                Path(filename).write_bytes(b"mock track")
                Path(filename + ".part").write_bytes(b"old fragment")
        with patch.dict(sys.modules, {"yt_dlp": types.SimpleNamespace(YoutubeDL=FakeYoutubeDL)}):
            with tempfile.TemporaryDirectory() as directory:
                data = json.loads(download_video(SAMPLE_SHORTS, directory))
                self.assertTrue(Path(data["video"]).is_file())
                self.assertTrue(Path(data["audio"]).is_file())
                self.assertEqual(sorted(p.suffix for p in Path(directory).iterdir()),
                                 [".m4a", ".mp4"])
        self.assertEqual(seen, ["137", "140"])


    def test_fallback_to_android_when_default_has_no_formats(self):
        seen = []
        class FakeYoutubeDL:
            def __init__(self, opts):
                self.opts = opts
            def __enter__(self):
                return self
            def __exit__(self, *args):
                return None
            def extract_info(self, url, download, process=True):
                client = (self.opts.get("extractor_args") or {}).get(
                    "youtube", {}).get("player_client")
                seen.append((client, download, process))
                if not download:
                    if not client:
                        return {"formats": []}
                    return {"formats": [
                        {"format_id": "18", "ext": "mp4", "vcodec": "avc1",
                         "acodec": "mp4a.40.2", "url": "https://example.test/18"}
                    ]}
                self.assert_from_android = client == ["android"]
                if not self.assert_from_android:
                    raise RuntimeError("Wrong player client")
                Path(self.opts["outtmpl"].replace("%(ext)s", "mp4")).write_bytes(b"ok")
        with patch.dict(sys.modules, {"yt_dlp": types.SimpleNamespace(YoutubeDL=FakeYoutubeDL)}):
            with tempfile.TemporaryDirectory() as directory:
                result = download_video(
                    "https://m.youtube.com/shorts/J5He-tp7LAU", directory)
                self.assertTrue(Path(result).is_file())
        self.assertEqual(seen, [
            (None, False, False),
            (["android"], False, False),
            (["android"], True, True)
        ])

    def test_no_client_formats_reports_safe_counts(self):
        class FakeYoutubeDL:
            def __init__(self, opts):
                self.opts = opts
            def __enter__(self):
                return self
            def __exit__(self, *args):
                return None
            def extract_info(self, url, download, process=True):
                self.assert_probe = not download and process is False
                if not self.assert_probe:
                    raise RuntimeError("unexpected download")
                return {"formats": []}
        with patch.dict(sys.modules, {"yt_dlp": types.SimpleNamespace(YoutubeDL=FakeYoutubeDL)}):
            with tempfile.TemporaryDirectory() as directory:
                with self.assertRaisesRegex(RuntimeError, "0 total, 0 accessible") as cm:
                    download_video(SAMPLE_SHORTS, directory)
                self.assertIn("JavaScript runtime", str(cm.exception))
                self.assertNotIn("https://", str(cm.exception))

    def test_download_format_disappears_has_helpful_error(self):
        class FakeYoutubeDL:
            def __init__(self, opts):
                self.opts = opts
            def __enter__(self):
                return self
            def __exit__(self, *args):
                return None
            def extract_info(self, url, download, process=True):
                if not download:
                    return {"formats": [
                        {"format_id": "18", "ext": "mp4", "vcodec": "avc1",
                         "acodec": "mp4a.40.2", "url": "https://example.test/18"}
                    ]}
                raise RuntimeError("Requested format is not available")
        with patch.dict(sys.modules, {"yt_dlp": types.SimpleNamespace(YoutubeDL=FakeYoutubeDL)}):
            with tempfile.TemporaryDirectory() as directory:
                with self.assertRaisesRegex(RuntimeError, "between inspection and download"):
                    download_video(SAMPLE_SHORTS, directory)


    def test_stream_403_switches_client_then_downloads(self):
        events = []
        class FakeYoutubeDL:
            def __init__(self, opts):
                self.opts = opts
            def __enter__(self):
                return self
            def __exit__(self, *args):
                return None
            def extract_info(self, url, download, process=True):
                client = (self.opts.get("extractor_args") or {}).get(
                    "youtube", {}).get("player_client")
                events.append((client, download, process))
                if not download:
                    return {"formats": [
                        {"format_id": "18", "ext": "mp4", "vcodec": "avc1",
                         "acodec": "mp4a.40.2", "url": "https://example.test/18"}
                    ]}
                target = Path(self.opts["outtmpl"].replace("%(ext)s", "mp4"))
                if not client:
                    # A failing CDN may leave partial bytes. They must not be reused.
                    Path(str(target) + ".part").write_bytes(b"partial")
                    raise RuntimeError("HTTP Error 403: Forbidden")
                self.assert_clean = not list(target.parent.glob("*.part"))
                if not self.assert_clean:
                    raise RuntimeError("stale partial file")
                target.write_bytes(b"valid mp4")
        with patch.dict(sys.modules, {"yt_dlp": types.SimpleNamespace(YoutubeDL=FakeYoutubeDL)}):
            with tempfile.TemporaryDirectory() as directory:
                path = Path(download_video(
                    "https://m.youtube.com/shorts/J5He-tp7LAU", directory))
                self.assertTrue(path.is_file())
                self.assertEqual(sorted(p.suffix for p in Path(directory).iterdir()), [".mp4"])
        self.assertEqual(events, [
            (None, False, False),
            (None, True, True),
            (["android"], False, False),
            (["android"], True, True),
        ])

    def test_403_on_both_clients_gives_safe_bounded_error(self):
        events = []
        class FakeYoutubeDL:
            def __init__(self, opts):
                self.opts = opts
            def __enter__(self):
                return self
            def __exit__(self, *args):
                return None
            def extract_info(self, url, download, process=True):
                client = (self.opts.get("extractor_args") or {}).get(
                    "youtube", {}).get("player_client")
                events.append((client, download))
                if not download:
                    return {"formats": [
                        {"format_id": "18", "ext": "mp4", "vcodec": "avc1",
                         "acodec": "mp4a.40.2", "url": "https://private.example/signed-secret"}
                    ]}
                raise RuntimeError(
                    "ERROR: unable to download video data: "
                    "HTTP Error 403: Forbidden https://private.example/signed-secret")
        with patch.dict(sys.modules, {"yt_dlp": types.SimpleNamespace(YoutubeDL=FakeYoutubeDL)}):
            with tempfile.TemporaryDirectory() as directory:
                with self.assertRaisesRegex(RuntimeError, "YouTube returned HTTP 403") as ctx:
                    download_video(SAMPLE_SHORTS, directory)
                self.assertIn("default: stream HTTP 403", str(ctx.exception))
                self.assertIn("android: stream HTTP 403", str(ctx.exception))
                self.assertNotIn("signed-secret", str(ctx.exception))
        self.assertEqual(events, [
            (None, False), (None, True), (["android"], False), (["android"], True)
        ])

    def test_non_403_error_does_not_trigger_more_player_clients(self):
        events = []
        class FakeYoutubeDL:
            def __init__(self, opts):
                self.opts = opts
            def __enter__(self):
                return self
            def __exit__(self, *args):
                return None
            def extract_info(self, url, download, process=True):
                client = (self.opts.get("extractor_args") or {}).get(
                    "youtube", {}).get("player_client")
                events.append(client)
                if not download:
                    return {"formats": [
                        {"format_id": "18", "ext": "mp4", "vcodec": "avc1",
                         "acodec": "mp4a.40.2", "url": "https://example.test/18"}
                    ]}
                raise RuntimeError("Unable to resolve host: No address associated with hostname")
        with patch.dict(sys.modules, {"yt_dlp": types.SimpleNamespace(YoutubeDL=FakeYoutubeDL)}):
            with tempfile.TemporaryDirectory() as directory:
                with self.assertRaisesRegex(RuntimeError, "No address associated"):
                    download_video(SAMPLE_SHORTS, directory)
        self.assertEqual(events, [None, None])


if __name__ == "__main__":
    unittest.main()
