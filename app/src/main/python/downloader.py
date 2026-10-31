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


def choose_shorts_formats(formats):
    """Choose a compatible combined file or a MP4 + M4A pair for Android muxing."""
    candidates = [item for item in (formats or [])
                  if item.get("format_id") and item.get("url")
                  and not item.get("has_drm")]

    def video(item):
        return item.get("vcodec") not in (None, "none")

    def audio(item):
        return item.get("acodec") not in (None, "none")

    def quality(item):
        return (item.get("height") or 0, item.get("tbr") or 0)

    # A genuine muxed MP4/WebM is easiest for Android and keeps an existing
    # lower-resolution option working without a transcoder.
    combined = [item for item in candidates
                if video(item) and audio(item) and item.get("ext") in ("mp4", "webm")]
    if combined:
        selected = max(combined, key=lambda x: (x.get("ext") == "mp4", *quality(x)))
        return ("combined", selected["format_id"], None)

    # No FFmpeg. MediaMuxer can remux AVC/HEVC + AAC into MP4 without transcoding.
    video_tracks = [item for item in candidates
                    if video(item) and not audio(item) and item.get("ext") == "mp4"
                    and str(item.get("vcodec", "")).startswith(("avc1", "hev1", "hvc1"))]
    audio_tracks = [item for item in candidates
                    if audio(item) and not video(item) and item.get("ext") == "m4a"
                    and str(item.get("acodec", "")).startswith("mp4a")]
    if video_tracks and audio_tracks:
        selected_video = max(video_tracks, key=lambda x: (
            str(x.get("vcodec", "")).startswith("avc1"), *quality(x)))
        selected_audio = max(audio_tracks, key=lambda x: (x.get("abr") or 0, x.get("tbr") or 0))
        return ("split", selected_video["format_id"], selected_audio["format_id"])

    raise RuntimeError(
        "No compatible Shorts formats: need combined MP4/WebM or AVC/HEVC MP4 + AAC M4A. "
        "YouTube format extraction may require an external JS runtime and yt-dlp-ejs."
    )


def download_shorts(url, directory, prefix, listener, report, check_cancelled):
    """Return a media file path, or JSON with video/audio paths to remux on Android."""
    import json
    from yt_dlp import YoutubeDL

    base_options = {
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "retries": 3,
        "fragment_retries": 3,
        "socket_timeout": 30,
        "cachedir": False,
        # Request all formats for metadata; a format selector demanding combined
        # streams would fail for typical Shorts.
        "format": "all",
    }
    report(0, "Checking available Shorts formats ...")
    check_cancelled()
    with YoutubeDL(base_options) as ydl:
        details = ydl.extract_info(url, download=False)
    check_cancelled()
    mode, video_id, audio_id = choose_shorts_formats(details.get("formats", []))

    def download_one(format_id, label, start, span):
        last = -1
        def hook(state):
            nonlocal last
            check_cancelled()
            if state.get("status") == "downloading":
                received = state.get("downloaded_bytes") or 0
                total = state.get("total_bytes") or state.get("total_bytes_estimate") or 0
                pct = min(99, int(100 * received / total)) if total else 0
                if pct >= last + 2:
                    last = pct
                    report(start + pct * span / 100.0, "Downloading Shorts {}: {}%".format(label, pct))
        options = dict(base_options,
                       format=str(format_id),
                       outtmpl=str(directory / (prefix + label + ".%(ext)s")),
                       progress_hooks=[hook])
        check_cancelled()
        with YoutubeDL(options) as ydl:
            ydl.extract_info(url, download=True)
        check_cancelled()
        files = [p for p in directory.glob(prefix + label + ".*")
                 if p.is_file() and p.suffix.lower() in _VIDEO.union({".m4a"})
                 and p.stat().st_size > 0]
        if not files:
            raise RuntimeError("Missing downloaded Shorts {} track".format(label))
        return max(files, key=lambda p: p.stat().st_size)

    if mode == "combined":
        return str(download_one(video_id, "combined", 5, 90))
    video_file = download_one(video_id, "video", 5, 45)
    audio_file = download_one(audio_id, "audio", 50, 40)
    report(92, "Preparing Android audio/video merge ...")
    return json.dumps({"video": str(video_file), "audio": str(audio_file)})

def download_video(raw_url, output_dir, listener=None, instagram_cookies=None):
    from yt_dlp import YoutubeDL
    url, platform = normalize_supported_url(raw_url)
    directory = Path(output_dir)
    directory.mkdir(parents=True, exist_ok=True)
    prefix = "{}_{}_".format(platform, uuid4().hex)
    last = -1
    selected = None
    retained = set()

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
        # Prefer a single MP4 carrying picture and audio, then any combined file.
        # Instagram can also expose video-only formats: allow those as fallback
        # rather than rejecting an otherwise downloadable Reel.
        # No FFmpeg is bundled, so we never select separate tracks to merge.
        "format": "best[ext=mp4]/best/bv*[ext=mp4]/bv*",
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
        if platform == "shorts":
            output = download_shorts(
                url, directory, prefix, listener, report,
                lambda: _check_cancelled(listener))
            if output.startswith("{"):
                import json
                separate = json.loads(output)
                retained.update((Path(separate["video"]), Path(separate["audio"])))
            else:
                retained.add(Path(output))
            return output
        # Only explicitly imported Instagram cookies are used. YouTube Shorts
        # remains anonymous and never receives the Instagram session.
        if instagram_cookies:
            cookies = Path(instagram_cookies)
            if not cookies.is_file():
                raise ValueError("Imported Instagram session file was not found")
            options["cookiefile"] = str(cookies)
        report(0, "Connecting to Instagram ...")
        try:
            with YoutubeDL(options) as ydl:
                _check_cancelled(listener)
                ydl.extract_info(url, download=True)
        except Exception as exc:
            message = str(exc).lower()
            if ("can't be seen by certain audiences" in message
                    or "content isn't available to everyone" in message):
                raise RuntimeError(
                    "Instagram restricted this Reel to certain viewers. "
                    "If your signed-in Instagram account can play it, import "
                    "that account's Netscape cookies.txt in Insta Saver and retry. "
                    "An account without viewing permission cannot download it."
                ) from None
            if "requested format is not available" in message:
                # Safe diagnostics: do not log cookies, signed media URLs,
                # account identifiers, or the extracted video metadata.
                # Format enumeration uses the SAME opt-in cookie session.
                _check_cancelled(listener)
                try:
                    inspection_options = dict(options)
                    inspection_options["format"] = "all"
                    inspection_options["skip_download"] = True
                    with YoutubeDL(inspection_options) as probe:
                        details = probe.extract_info(url, download=False) or {}
                    formats = details.get("formats") or []
                    video_formats = [
                        item for item in formats if
                        item.get("vcodec") != "none"
                        and item.get("ext") in ("mp4", "webm", "mkv", "mov")
                    ]
                    audio_only = sum(
                        1 for item in formats
                        if item.get("vcodec") == "none"
                        and item.get("acodec") not in (None, "none")
                    )
                    diagnostics = (
                        "yt-dlp exposed {} total formats, {} possible video formats "
                        "and {} audio-only formats."
                    ).format(len(formats), len(video_formats), audio_only)
                    if not video_formats:
                        diagnostics += (
                            " No usable video stream was returned; verify the Reel "
                            "plays in the same browser account. Instagram may also "
                            "be blocking extraction."
                        )
                    else:
                        # Keep only a short, non-sensitive list of types for logs.
                        types = sorted(set(str(item.get("ext") or "unknown")
                                           for item in video_formats))
                        diagnostics += " Video containers: {}.".format(
                            ", ".join(types[:5]))
                except DownloadCancelled:
                    raise
                except Exception:
                    diagnostics = (
                        "Could not enumerate Instagram video formats. "
                        "The login session or extraction may be blocked."
                    )
                raise RuntimeError("Instagram format selection failed. " + diagnostics) from None
            raise
        _check_cancelled(listener)
        videos = [p for p in directory.glob(prefix + "*")
                  if p.is_file() and p.suffix.lower() in _VIDEO and p.stat().st_size > 0]
        if not videos:
            raise RuntimeError("No compatible video file found after extraction")
        selected = max(videos, key=lambda p: p.stat().st_mtime)
        retained.add(selected)
        return str(selected)
    finally:
        # yt-dlp can leave .part, fragments and metadata after failures/cancellation.
        # Files from other downloads have distinct UUID prefixes and are untouched.
        for path in directory.glob(prefix + "*"):
            if path.is_file() and path not in retained:
                try:
                    path.unlink()
                except OSError:
                    pass


def download_reel(raw_url, output_dir, listener=None):
    """Backward-compatible alias for existing callers and offline tests."""
    return download_video(raw_url, output_dir, listener)
