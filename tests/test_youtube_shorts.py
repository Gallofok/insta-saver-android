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
            def extract_info(self, url, download):
                seen.append((url, download, self.options["format"]))
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
        self.assertEqual(seen[0], (NORMALIZED_SHORTS, False, "all"))
        self.assertEqual(seen[1], (NORMALIZED_SHORTS, True, "18"))

    def test_mock_split_download_returns_tracks_and_cleans_sidecars(self):
        seen = []
        class FakeYoutubeDL:
            def __init__(self, options):
                self.options = options
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def extract_info(self, url, download):
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


if __name__ == "__main__":
    unittest.main()
