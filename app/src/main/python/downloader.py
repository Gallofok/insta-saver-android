"""Download publicly available Instagram video with local yt-dlp extraction."""
from pathlib import Path
from urllib.parse import urlsplit
from uuid import uuid4

_HOSTS = {"instagram.com", "www.instagram.com", "m.instagram.com"}
_TYPES = {"reel", "reels", "p", "tv"}
_VIDEO = {".mp4", ".webm", ".mov", ".mkv"}


def normalize_instagram_url(value):
    parts = urlsplit((value or "").strip())
    if parts.scheme not in ("https", "http") or (parts.hostname or "").lower() not in _HOSTS:
        raise ValueError("Only public Instagram post/reel links are supported")
    segments = [s for s in parts.path.split("/") if s]
    if len(segments) < 2 or segments[0].lower() not in _TYPES:
        raise ValueError("Expected /reel/SHORTCODE/ or /p/SHORTCODE/")
    shortcode = segments[1]
    if not shortcode or not all(c.isalnum() or c in "-_" for c in shortcode):
        raise ValueError("Invalid Instagram shortcode")
    return "https://www.instagram.com/{}/{}/".format(segments[0].lower(), shortcode)


def download_reel(raw_url, output_dir, listener=None):
    from yt_dlp import YoutubeDL
    url = normalize_instagram_url(raw_url)
    directory = Path(output_dir)
    directory.mkdir(parents=True, exist_ok=True)
    prefix = "insta_{}_".format(uuid4().hex)
    last = -1

    def report(pct, message):
        if listener is not None:
            listener.report(float(pct), str(message))

    def hook(state):
        nonlocal last
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
        "format": "best[ext=mp4][vcodec!=none][acodec!=none]/best[vcodec!=none][acodec!=none]/best",
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "progress_hooks": [hook],
        "retries": 3,
        "fragment_retries": 3,
        "socket_timeout": 30,
        "cachedir": False,
    }
    report(0, "Connecting to Instagram ...")
    with YoutubeDL(options) as ydl:
        ydl.extract_info(url, download=True)
    videos = [p for p in directory.glob(prefix + "*")
              if p.is_file() and p.suffix.lower() in _VIDEO and p.stat().st_size > 0]
    if not videos:
        raise RuntimeError("No compatible video file found (FFmpeg merging is not supported yet)")
    return str(max(videos, key=lambda p: p.stat().st_mtime))
