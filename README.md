# Insta Saver Android

Experimental **on-device** Instagram Reel and **YouTube Shorts** downloader for Android 10+ (ARM64).
The native Java interface invokes embedded Python **yt-dlp** through Chaquopy.
**No Media Saver server** is needed.

## Features
- Instagram Reel or YouTube Shorts → **Share** → **Insta Saver** receives a link and starts downloading.
- Or paste a public Reel/post/Shorts URL in the app and press **Download video**.
- Saves to **Movies/InstaSaver** using Android MediaStore without broad storage permissions.
- **My downloaded videos** opens an in-app, two-column gallery of videos saved in `Movies/InstaSaver`, with thumbnails, duration, saved date, a refresh button, and tap-to-play using an in-app Android player.
- Gallery playback is offline and does **not** require an Instagram/YouTube connection.
- The gallery intentionally lists app-owned MediaStore videos without asking for broad media access permissions; after uninstall/reinstall, Android may treat older saved videos as media owned by another app installation and not expose them without additional permission. Updating the APK in place preserves app ownership and imported cookies.
- Up to 80 newest videos are displayed, newest first; existing downloads are not recopied or duplicated.
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
- **v0.3.3 format fallback:** Instagram downloads now prefer a combined MP4, fall back to any combined video/audio stream, and finally accept a video-only stream if no combined format exists. Video-only downloads will be silent. When no compatible format is extracted, the error includes safe format counts (not URLs or Cookie contents); zero video formats does not necessarily mean the Reel has been removed.
- If a v0.3.3 build still fails, copy the **debug log** after verifying it does not contain private data. Avoid repeated rapid attempts that could trigger Instagram limits.
- yt-dlp uses a single combined audio/video format where available. **No FFmpeg is bundled**; formats which require merging may fail.
- No background service or analytics. Instagram authentication is **optional** via user-imported Netscape-format `cookies.txt`; passwords are never requested by the app.
- **Importing an Instagram session:** First confirm the Reel plays in your own signed-in account. Export **Instagram-only** cookies in Netscape format from a browser you trust (for example, a dedicated browser profile), save as `cookies.txt`, transfer it to your phone, and tap **Import Instagram cookies.txt (optional)** in Insta Saver. Tap **Remove Instagram login session** to delete the locally stored session. The app stores only Instagram-domain cookie rows in Android private app storage and does not send them to a backend.
- A cookies file is sensitive account access material. Never commit it to GitHub, send it in a chat, upload it to a conversion website, or include it in debug logs. Revoke active sessions in Instagram if exposed. Use only an account allowed to view the Reel.
- This does **not** bypass audience, age, geographic, or account restrictions. It does not guarantee Instagram access, and may need fresh cookies after expiration.
- Only download media you own or have authorization to save.
- This repository has not been validated on a physical device yet.

## Development
- `app/src/main/java/com/gallofok/instasaver/MainActivity.java` — download UI, shares, gallery saving.
- `app/src/main/java/com/gallofok/instasaver/DownloadsActivity.java` — in-app saved-video grid and thumbnails.
- `app/src/main/java/com/gallofok/instasaver/VideoPlayerActivity.java` — offline video playback.
- `app/src/main/python/downloader.py` — local yt-dlp extraction and progress.
- `tests/test_downloader.py` and `tests/test_youtube_shorts.py` — offline Python tests.
- `.github/workflows/build-apk.yml` — automated Gradle debug APK.

## Local commands
```bash
python -m unittest discover -s tests -v
gradle :app:assembleDebug
```

Build toolchain: JDK 17, Python 3.11, Gradle 8.11.1, Android Gradle Plugin 8.9.2, Chaquopy 17.2.0, Android SDK 35.
