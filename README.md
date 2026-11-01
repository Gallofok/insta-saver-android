# Insta Saver Android

Experimental **on-device** Instagram Reel and **YouTube Shorts** downloader for Android 10+ (ARM64).
The native Java interface invokes embedded Python **yt-dlp** through Chaquopy.
**No Media Saver server** is needed.

## YouTube stream 403 recovery (v0.3.9)

- v0.3.8 sometimes found suitable Shorts media formats but the YouTube content-delivery endpoint then returned **HTTP 403 Forbidden** when downloading the actual bytes.
- v0.3.9 keeps the blue UI, Instagram Cookie handling, local media browser, and permanent APK signature unchanged. On a stream-specific 403, it removes only this in-progress download's temporary files and tries the alternate **Android** YouTube client once; it will not repeatedly retry the same unavailable stream.
- If the Android client also returns 403 (or no suitable format), it reports a sanitized account/network/JS-challenge diagnostic and stops. Errors unrelated to media stream 403, such as DNS errors, are not incorrectly retried as client failures.
- This is a bounded recovery attempt, **not** guaranteed YouTube compatibility: some formats require yt-dlp-ejs/JavaScript runtime or may be blocked by YouTube's delivery endpoints. It does not bypass videos the viewer isn't permitted to access.
- Stable-signed test build: `fix/shorts-403-recovery-v039-20261101` versionCode 12/versionName 0.3.9, install as an **update** over v0.3.8.

## Shorts compatibility hotfix (v0.3.8)

- This update retains the approved blue interface, Instagram authentication, offline gallery and permanent signing identity.
- For YouTube Shorts, the diagnostic pass now retrieves **unprocessed format metadata** rather than setting `format=all`, which could fail before format inspection.
- If the default YouTube player clients produce no Android-compatible combined stream or AVC/HEVC MP4 + AAC/M4A pair, the downloader tries the alternative `android` player client once. This is not a guarantee: YouTube may restrict some players or formats, and the app still does not bundle a JavaScript runtime / EJS challenge solver.
- Errors and progress show bounded format counts and whether a fallback client was attempted. The app does **not** log signed media links, authorization headers or Cookie values.
- Extra offline regression tests cover the fallback, empty format lists, and a format vanishing between inspection and download.
- Install this update using a **stable-signed APK**, built from branch `fix/shorts-format-diagnostics-v038-20261101` with `versionCode 11` / `versionName 0.3.8`. This should update v0.3.7 directly without uninstalling.

## Blue UI test release (v0.3.7)

This branch changes **presentation only** and preserves the existing downloader behavior, supported URL rules, Instagram Cookie file handling, offline media gallery, Android video player, permissions, and permanent signing setup.

- Blue/cyan header with Insta Saver branding.
- Rounded URL card with Paste and Download buttons, plus platform labels.
- Quick tiles for My downloads, Import Instagram cookies, and Copy debug log.
- Status/progress/cancel card with the same progress callbacks and cancellation logic.
- A real Recent downloads list sourced from the existing `Movies/InstaSaver` MediaStore folder (no sample videos are manufactured); tap a row to play with the existing player.
- Bottom Home / Downloads / Settings navigation; Settings preserves Cookie removal, debug log viewing, and video library links.
- Downloads gallery uses matching blue colors but retains its original query/permission logic.

**Update without uninstalling:** manually run the **Build debug APK** workflow on the `feat/blue-ui-v037-20261101` branch and use its **insta-saver-stable-signed-apk** artifact, not an ephemeral PR debug APK. Requires the permanent encrypted signing key already initialized in the repo; v0.3.7 uses `versionCode 10` following v0.3.6's code 9.

## Features
- Instagram Reel or YouTube Shorts → **Share** → **Insta Saver** receives a link and starts downloading.
- Or paste a public Reel/post/Shorts URL in the app and press **Download video**.
- Saves to **Movies/InstaSaver** using Android MediaStore without broad storage permissions.
- **My downloaded videos** opens an in-app, two-column gallery of videos saved in `Movies/InstaSaver`, with thumbnails, duration, saved date, a refresh button, and tap-to-play using an in-app Android player.
- Gallery playback is offline and does **not** require an Instagram/YouTube connection.
- Gallery v0.3.5: If old files exist in `Movies/InstaSaver` but the gallery is blank, tap **Allow access to older downloads**, then choose **Allow all videos** in Android's permission dialog. On Android 14+, **Select videos** grants partial access; the app then sees only the selected items and already owned media. The gallery still filters to the `Movies/InstaSaver` folder even with full permission.
- No permission is requested when opening Insta Saver or downloading. The permission request happens only when the user taps the gallery's access button.
- This is important after uninstall/reinstall: Android can remove app ownership of previously created media, even when the files remain in the folder. Updating the APK in place (when possible) preserves app ownership and imported Instagram cookies.
- If full access is granted and files still do not appear, confirm their physical folder and that Android MediaStore indexes them as videos.
- Up to 80 newest videos are displayed, newest first; existing downloads are not recopied or duplicated.
- Visible progress, error messages and a copyable debug log.
- **Cancel download** while yt-dlp is running. Partial downloads are cleaned up on cancellation or failure.
- The download runs in the foreground. Keep the app open.

## Permanent APK signing: Pixel-only setup (v0.3.6 and later)

Older GitHub-hosted debug builds used disposable signing keys, so Android could not install them as updates. **The first installation of v0.3.6 with the new signing key will require uninstalling the older APK once. After that, all signed updates can install on top, provided the key and `applicationId` stay unchanged and each `versionCode` increases.** Uninstalling clears app-private Cookie files, but downloaded media in the shared `Movies/InstaSaver` folder should remain.

Set this up only once, using the GitHub website on your Pixel:
1. In this repository, open **Settings → Secrets and variables → Actions → New repository secret**. On a small screen, enable the browser's **Desktop site** mode if necessary.
2. Use secret name `INSTA_SAVER_SIGNING_PASSPHRASE`. For its value, generate a **unique random 32+ character password** in a password manager; back it up securely. Do not send the password to anyone or place it in source files, logs or issues. Losing it means losing the ability to sign compatible updates. Unicode passwords are supported: the bootstrap hashes the UTF-8 passphrase to a stable ASCII password for Java keytool/PKCS12, while OpenSSL encrypts the keystore with the original passphrase.
3. Open **Actions → Initialize permanent APK signing key**. Select branch **main** and click **Run workflow**. This runs once: it creates a PKCS#12 key in a temporary CI directory, encrypts it with AES-256 + PBKDF2 and commits **only the encrypted keystore** to `main` at `.github/signing/insta-saver-signing.p12.enc`. Re-running it will refuse to replace the key.
4. Open **Actions → Build debug APK**. Choose **Run workflow**, select the code branch containing the latest version (currently `fix/stable-apk-signing-20261031`), then run it manually. **Only manually triggered builds get access to the secret and publish an installable APK.** Ordinary PR builds do their tests without the secret and publish no APK.
5. When the run succeeds, open **Artifacts → insta-saver-stable-signed-apk**, download the ZIP, unzip and install `app-debug.apk`.

The first install from an ephemeral-signed APK requires uninstalling that previous APK because the prior signing key cannot be recovered. Afterwards, installing newer stable-signed APKs directly should preserve imported cookies and app settings. Keep the signing secret permanently and never trigger the key initialization again. **Do not change the encryption password without also re-encrypting the stored keystore**; changing the secret alone makes builds fail. The signing key is for personal sideloading/testing, not a Play Store production release.

If the GitHub bootstrap workflow cannot push its encrypted keystore due to branch rules or disabled Action write permissions, check repository **Settings → Actions → General → Workflow permissions** and branch protection rather than committing an unencrypted key.

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
