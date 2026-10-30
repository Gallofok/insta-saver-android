# Insta Saver Android

Experimental **on-device** Instagram Reel video downloader for Android 10+ (ARM64).
The native Java interface invokes embedded Python **yt-dlp** through Chaquopy.
**No Media Saver server** is needed.

## Features
- Instagram → **Share** → **Insta Saver** receives a link and starts downloading.
- Or paste a public Reel/post URL in the app and press **Download video**.
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
- Only publicly accessible Instagram Reel/post URLs are supported.
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
- `tests/test_downloader.py` — offline Python tests.
- `.github/workflows/build-apk.yml` — automated Gradle debug APK.

## Local commands
```bash
python -m unittest discover -s tests -v
gradle :app:assembleDebug
```

Build toolchain: JDK 17, Python 3.11, Gradle 8.11.1, Android Gradle Plugin 8.9.2, Chaquopy 17.2.0, Android SDK 35.
