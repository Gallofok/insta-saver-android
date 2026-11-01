# Insta Saver Android

Experimental **on-device** Instagram Reel and **YouTube Shorts** downloader for Android 10+ (ARM64).
The native Java interface invokes embedded Python **yt-dlp** through Chaquopy.
**No Media Saver server** is needed.

## Features
- Instagram Reel or YouTube Shorts → **Share** → **Insta Saver** receives a link and starts downloading.
- Or paste a public Reel/post/Shorts URL in the app and press **Download video**.
- Saves to **Movies/InstaSaver** using Android MediaStore without broad storage permissions.
- Visible progress, error messages and a copyable debug log.
- **Cancel download** while yt-dlp is running. Partial downloads are cleaned up on cancellation or failure.
- The download runs in the foreground. Keep the app open.

## GitHub Actions: build an APK without a computer
1. Open this repository's **Actions** tab.
2. Select **Build debug APK**, then **Run workflow** (or push to main).
3. After the run succeeds, open the run's **Artifacts** section.
4. Download **insta-saver-debug-apk**, extract the ZIP and install `app-debug.apk`.

A debug APK is installable for testing, but is *not* a Play Store release and is signed with a debug certificate.

## Scope and limits
- Supported platforms: public Instagram Reel/post and **YouTube Shorts only**. YouTube watch, playlists, channels and youtu.be links are not supported.
- Example Shorts validation/test URL: `https://m.youtube.com/shorts/xtcECX1rnuw` (canonicalized to `https://www.youtube.com/shorts/xtcECX1rnuw`).
- **YouTube Shorts are experimental and not yet validated on-device**: recent yt-dlp YouTube extraction needs an external JavaScript runtime plus yt-dlp-ejs for full support. Neither is bundled by this APK; without them, some (or all) Shorts formats may be unavailable.
- Shorts first use an available combined MP4/WebM video with audio. If none exists, the app selects separate H.264/H.265 MP4 video and AAC/M4A audio streams and uses Android's built-in MediaMuxer to combine them **without FFmpeg**. Other codec combinations may still fail.
- The UI now reports when only incompatible formats are available. A missing JavaScript runtime can prevent yt-dlp from exposing playable formats, so this change does not guarantee successful downloading of every Short.
- Real device test requested: `https://m.youtube.com/shorts/xtcECX1rnuw`; verify playable video *with audio* in the gallery.
- Automated tests validate the URL and use a fake yt-dlp extractor; they do not download the sample video or prove real Shorts extraction works.
- Cancellation can take until the next download progress callback or extraction check; it is not immediate during network setup.
- APK builds and offline Python tests also run for pull requests targeting main.
- Instagram may require login, block requests or change its extraction protocol.
- yt-dlp uses a single combined audio/video format where available. **No FFmpeg is bundled**; formats which require merging may fail.
- No background service, cookie import, account handling, or analytics.
- Only download media you own or have authorization to save.
- This repository has not been validated on a physical device yet.

## Development
- `app/src/main/java/com/gallofok/instasaver/MainActivity.java` — UI, shares, gallery saving.
- `app/src/main/python/downloader.py` — local yt-dlp extraction and progress.
- `tests/test_downloader.py` and `tests/test_youtube_shorts.py` — offline Python tests.
- `.github/workflows/build-apk.yml` — automated Gradle debug APK.

## Local commands
```bash
python -m unittest discover -s tests -v
gradle :app:assembleDebug
```

Build toolchain: JDK 17, Python 3.11, Gradle 8.11.1, Android Gradle Plugin 8.9.2, Chaquopy 17.2.0, Android SDK 35.
