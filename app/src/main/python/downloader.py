"""Download public Instagram posts and YouTube Shorts with local yt-dlp."""
import re
from pathlib import Path
from urllib.parse import urlsplit
from uuid import uuid4

_INSTAGRAM_HOSTS = {"instagram.com", "www.instagram.com", "m.instagram.com"}
_YOUTUBE_HOSTS = {"youtube.com", "www.youtube.com", "m.youtube.com"}
_TYPES = {"reel", "reels", "p", "tv"}
_SHORTS_PATH = re.compile(r"^/shorts/([A-Za-z0-9_-]{11})/?$")
_VIDEO = {".mp4", ".webm", ".mov", ".mkv"}

class DownloadCancelled(RuntimeError):
    """Raised when the Android UI requests cancellation."""


def _check_cancelled(listener):
    check = getattr(listener, "isCancelled", None)
    if callable(check) and check():
        raise DownloadCancelled("Download cancelled by user")


def normalize_instagram_url(value):
    parts = urlsplit((value or "").strip())
    if parts.scheme not in ("https", "http") or (parts.hostname or "").lower() not in _INSTAGRAM_HOSTS:
        raise ValueError("Only public Instagram post/reel links are supported")
    segments = [s for s in parts.path.split("/") if s]
    if len(segments) < 2 or segments[0].lower() not in _TYPES:
        raise ValueError("Expected /reel/SHORTCODE/ or /p/SHORTCODE/")
    shortcode = segments[1]
    if not shortcode or not all(c.isalnum() or c in "-_" for c in shortcode):
        raise ValueError("Invalid Instagram shortcode")
    return "https://www.instagram.com/{}/{}/".format(segments[0].lower(), shortcode)


def normalize_youtube_shorts_url(value):
    """Only accept YouTube /shorts/ URLs, never watch, playlist or channel URLs."""
    parts = urlsplit((value or "").strip())
    if (parts.scheme not in ("https", "http")
            or (parts.hostname or "").lower() not in _YOUTUBE_HOSTS
            or parts.username is not None or parts.password is not None):
        raise ValueError("Only YouTube Shorts links are supported")
    match = _SHORTS_PATH.fullmatch(parts.path)
    if not match:
        raise ValueError("Expected a YouTube /shorts/VIDEO_ID URL")
    return "https://www.youtube.com/shorts/{}".format(match.group(1))


def normalize_supported_url(value):
    """Return a canonical URL and its supported source name."""
    parts = urlsplit((value or "").strip())
    host = (parts.hostname or "").lower()
    if host in _YOUTUBE_HOSTS:
        return normalize_youtube_shorts_url(value), "shorts"
    return normalize_instagram_url(value), "instagram"


def download_video(raw_url, output_dir, listener=None):
    from yt_dlp import YoutubeDL
    url, platform = normalize_supported_url(raw_url)
    directory = Path(output_dir)
    directory.mkdir(parents=True, exist_ok=True)
    prefix = "{}_{}_".format(platform, uuid4().hex)
    last = -1
    selected = None

    def report(pct, message):
        if listener is not None:
            listener.report(float(pct), str(message))

    def hook(state):
        nonlocal last
        _check_cancelled(listener)
        if state.get("status") == "downloading":
            received = state.get("downloaded_bytes") or 0
            total = state.get("total_bytes") or state.get("total_bytes_estimate") or 0
            pct = min(99, int(received * 100 / total)) if total else 0
            if pct >= last + 2:
                last = pct
                report(pct, "Downloading: {}%".format(pct))
        elif state.get("status") == "finished":
            report(99, "Preparing video for gallery ...")

    options = {
        "outtmpl": str(directory / (prefix + "%(id)s.%(ext)s")),
        # No FFmpeg is bundled: only select a single file containing BOTH video and audio.
        "format": "best[ext=mp4][vcodec!=none][acodec!=none]/best[vcodec!=none][acodec!=none]",
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "progress_hooks": [hook],
        "retries": 3,
        "fragment_retries": 3,
        "socket_timeout": 30,
        "cachedir": False,
    }
    try:
        _check_cancelled(listener)
        report(0, "Connecting to YouTube Shorts ..." if platform == "shorts"
               else "Connecting to Instagram ...")
        with YoutubeDL(options) as ydl:
            _check_cancelled(listener)
            ydl.extract_info(url, download=True)
        _check_cancelled(listener)
        videos = [p for p in directory.glob(prefix + "*")
                  if p.is_file() and p.suffix.lower() in _VIDEO and p.stat().st_size > 0]
        if not videos:
            raise RuntimeError("No combined video/audio format available (FFmpeg is not bundled)")
        selected = max(videos, key=lambda p: p.stat().st_mtime)
        return str(selected)
    finally:
        # yt-dlp can leave .part, fragments and metadata after failures/cancellation.
        # Files from other downloads have distinct UUID prefixes and are untouched.
        for path in directory.glob(prefix + "*"):
            if path.is_file() and path != selected:
                try:
                    path.unlink()
                except OSError:
                    pass


def download_reel(raw_url, output_dir, listener=None):
    """Backward-compatible alias for existing callers and offline tests."""
    return download_video(raw_url, output_dir, listener)
