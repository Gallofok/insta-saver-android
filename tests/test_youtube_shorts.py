"""Offline regression tests for supported YouTube Shorts links.

All examples are URL/mocked-extractor tests, NOT evidence of a real download.
"""
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app/src/main/python"))

from downloader import download_video, normalize_supported_url, normalize_youtube_shorts_url


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
                filename = self.options["outtmpl"].replace(
                    "%(id)s", "xtcECX1rnuw").replace("%(ext)s", "mp4")
                Path(filename).write_bytes(b"mock video with audio")
        with patch.dict(sys.modules, {"yt_dlp": types.SimpleNamespace(YoutubeDL=FakeYoutubeDL)}):
            with tempfile.TemporaryDirectory() as directory:
                path = Path(download_video(SAMPLE_SHORTS, directory))
                self.assertTrue(path.is_file())
                self.assertTrue(path.name.startswith("shorts_"))
        self.assertEqual(seen[0][:2], (NORMALIZED_SHORTS, True))
        self.assertIn("[vcodec!=none][acodec!=none]", seen[0][2])
        self.assertNotIn("bestvideo+", seen[0][2])


if __name__ == "__main__":
    unittest.main()
