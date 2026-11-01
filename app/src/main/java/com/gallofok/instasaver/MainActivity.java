package com.gallofok.instasaver;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.ContentResolver;
import android.graphics.Color;
import android.net.Uri;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.text.InputType;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small standalone Android UI; Python yt-dlp runs only on a worker thread. */
public class MainActivity extends Activity {
    private static final int PICK_INSTAGRAM_COOKIES = 731;
    private static final Pattern SUPPORTED_URL = Pattern.compile(
        "https?://(?:www\\.|m\\.)?instagram\\.com/(?:reel|reels|p|tv)/[A-Za-z0-9_-]+[^\\s]*"
        + "|https?://(?:www\\.|m\\.)?youtube\\.com/shorts/[A-Za-z0-9_-]{11}(?:[/?#][^\\s]*)?",
        Pattern.CASE_INSENSITIVE);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private EditText urlInput;
    private Button downloadButton;
    private Button cancelButton;
    private volatile ProgressCallback activeCallback;
    private ProgressBar progressBar;
    private TextView statusText;
    private TextView logText;
    private boolean downloading = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        createUi();
        handleIncoming(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncoming(intent);
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private void createUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(28), dp(20), dp(24));
        scroll.addView(content);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("Insta Saver");
        title.setTextColor(Color.rgb(30, 45, 82));
        title.setTextSize(28);
        content.addView(title);

        TextView intro = new TextView(this);
        intro.setText("Share an Instagram Reel or YouTube Short to this app, or paste its link. Downloads run on-device using yt-dlp.");
        intro.setTextSize(15);
        intro.setPadding(0, dp(10), 0, dp(18));
        content.addView(intro);

        urlInput = new EditText(this);
        urlInput.setHint("https://m.youtube.com/shorts/...");
        urlInput.setTextSize(14);
        urlInput.setSingleLine(false);
        urlInput.setMinLines(2);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        content.addView(urlInput, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        content.addView(controls);
        downloadButton = new Button(this);
        downloadButton.setText("Download video");
        controls.addView(downloadButton, new LinearLayout.LayoutParams(0, -2, 1));
        downloadButton.setOnClickListener(v -> download());
        Button paste = new Button(this);
        paste.setText("Paste");
        controls.addView(paste);
        paste.setOnClickListener(v -> pasteClipboard());

        // Video gallery is deliberately independent of network downloads.
        Button myDownloads = new Button(this);
        myDownloads.setText("My downloaded videos");
        content.addView(myDownloads);
        myDownloads.setOnClickListener(v ->
            startActivity(new Intent(this, DownloadsActivity.class)));

        Button importCookies = new Button(this);
        importCookies.setText("Import Instagram cookies.txt (optional)");
        content.addView(importCookies);
        importCookies.setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("*/*");
            startActivityForResult(pick, PICK_INSTAGRAM_COOKIES);
        });
        Button clearCookies = new Button(this);
        clearCookies.setText("Remove Instagram login session");
        content.addView(clearCookies);
        clearCookies.setOnClickListener(v -> {
            File cookies = instagramCookiesFile();
            boolean removed = !cookies.exists() || cookies.delete();
            status(removed ? "Imported Instagram session removed" : "Could not remove Instagram session");
            log(removed ? "Instagram cookies removed locally" : "Cannot delete imported Instagram cookies");
        });

        cancelButton = new Button(this);
        cancelButton.setText("Cancel download");
        cancelButton.setEnabled(false);
        content.addView(cancelButton);
        cancelButton.setOnClickListener(v -> {
            ProgressCallback callback = activeCallback;
            if (callback == null) return;
            callback.cancel();
            cancelButton.setEnabled(false);
            status("Cancelling download ...");
        });

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(-1, dp(14));
        bar.topMargin = dp(22);
        content.addView(progressBar, bar);

        statusText = new TextView(this);
        statusText.setText("Ready");
        statusText.setTextSize(16);
        statusText.setPadding(0, dp(12), 0, dp(10));
        content.addView(statusText);

        TextView dest = new TextView(this);
        dest.setText("Destination: Movies/InstaSaver");
        dest.setTextSize(13);
        content.addView(dest);

        Button copyLog = new Button(this);
        copyLog.setText("Copy debug log");
        content.addView(copyLog);
        copyLog.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Insta Saver log", logText.getText()));
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
        });
        logText = new TextView(this);
        logText.setTextSize(12);
        logText.setTextIsSelectable(true);
        logText.setPadding(0, dp(8), 0, dp(8));
        content.addView(logText);
        log("Initialized. Public Instagram posts and YouTube Shorts only. Keep app open while downloading.");
    }

    private File instagramCookiesFile() {
        // Internal app-only storage, never shared with or logged to GitHub.
        return new File(getFilesDir(), "instagram-cookies.txt");
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_INSTAGRAM_COOKIES && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            importInstagramCookies(data.getData());
        }
    }

    private void importInstagramCookies(Uri source) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream stream = getContentResolver().openInputStream(source)) {
                if (stream == null) throw new IOException("Cannot read selected file");
                byte[] chunk = new byte[8192];
                int read;
                while ((read = stream.read(chunk)) != -1) {
                    if (buffer.size() + read > 262144) throw new IOException("Cookie file is too large");
                    buffer.write(chunk, 0, read);
                }
            }

            String raw = new String(buffer.toByteArray(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
            if (!raw.startsWith("# Netscape HTTP Cookie File")
                    && !raw.startsWith("# HTTP Cookie File")) {
                throw new IOException("Select a Netscape-format cookies.txt file");
            }
            StringBuilder selected = new StringBuilder("# Netscape HTTP Cookie File\n");
            boolean hasSession = false;
            for (String line : raw.split("\n")) {
                if (line.isEmpty() || (line.startsWith("#") && !line.startsWith("#HttpOnly_"))) continue;
                String[] fields = line.split("\t", -1);
                if (fields.length != 7) continue;
                String domain = fields[0].replace("#HttpOnly_", "").toLowerCase(Locale.ROOT);
                if (!domain.equals("instagram.com") && !domain.endsWith(".instagram.com")) continue;
                selected.append(line).append('\n');
                if (fields[5].equals("sessionid") && !fields[6].isEmpty()) hasSession = true;
            }
            if (!hasSession) throw new IOException("No Instagram sessionid cookie found; export from a logged-in browser");
            try (FileOutputStream output = new FileOutputStream(instagramCookiesFile())) {
                output.write(selected.toString().getBytes(StandardCharsets.UTF_8));
            }
            status("Instagram login session imported");
            log("Imported Instagram cookies into private app storage (values hidden)");
            Toast.makeText(this, "Instagram session imported", Toast.LENGTH_SHORT).show();
        } catch (Exception problem) {
            status("Instagram cookie import failed");
            // Do not log raw cookies or arbitrary document contents.
            log("Cookie import error: " + problem.getMessage());
        }
    }

    private void pasteClipboard() {
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb.hasPrimaryClip() && cb.getPrimaryClip() != null && cb.getPrimaryClip().getItemCount() > 0) {
            CharSequence text = cb.getPrimaryClip().getItemAt(0).coerceToText(this);
            if (text != null) urlInput.setText(extractUrl(text.toString()));
        }
    }

    private static String extractUrl(String input) {
        Matcher matcher = SUPPORTED_URL.matcher(input);
        return matcher.find() ? matcher.group().replaceAll("[),.\\]]+$", "") : input.trim();
    }

    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())) {
            CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (shared != null) {
                urlInput.setText(extractUrl(shared.toString()));
                log("Received shared link");
                if (!downloading) download();
            }
        }
    }

    private void log(String value) {
        String previous = logText.getText().toString();
        if (previous.length() > 9000) previous = previous.substring(previous.length() - 7000);
        logText.setText(previous + value + "\n");
    }

    private void status(String value) { statusText.setText(value); }

    private void download() {
        if (downloading) return;
        final String source = extractUrl(urlInput.getText().toString());
        if (!SUPPORTED_URL.matcher(source).matches()) {
            status("Enter an Instagram Reel or YouTube Shorts URL");
            return;
        }
        urlInput.setText(source);
        downloading = true;
        downloadButton.setEnabled(false);
        progressBar.setProgress(0);
        status("Starting yt-dlp ...");
        log("Start: " + source);
        File cache = new File(getCacheDir(), "downloads");
        if (!cache.exists() && !cache.mkdirs()) {
            fail("Cannot create downloads directory");
            return;
        }
        final ProgressCallback callback = new ProgressCallback();
        activeCallback = callback;
        cancelButton.setEnabled(true);
        worker.execute(() -> {
            File downloadedFile = null;
            File videoTrack = null;
            File audioTrack = null;
            File mergedFile = null;
            try {
                if (callback.isCancelled()) throw new IOException("Cancelled by user");
                if (!Python.isStarted()) Python.start(new AndroidPlatform(getApplicationContext()));
                PyObject module = Python.getInstance().getModule("downloader");
                String cookiePath = source.toLowerCase(Locale.ROOT).contains("instagram.com/")
                        && instagramCookiesFile().isFile()
                        ? instagramCookiesFile().getAbsolutePath() : null;
                PyObject value = module.callAttr("download_video", source, cache.getAbsolutePath(), callback, cookiePath);
                String result = value.toString();
                if (result.startsWith("{")) {
                    JSONObject parts = new JSONObject(result);
                    videoTrack = new File(parts.getString("video"));
                    audioTrack = new File(parts.getString("audio"));
                    mergedFile = new File(cache, "shorts_merged_" + System.nanoTime() + ".mp4");
                    runOnUiThread(() -> status("Merging Shorts video and audio ..."));
                    Mp4TracksMuxer.merge(videoTrack, audioTrack, mergedFile, callback::isCancelled);
                    downloadedFile = mergedFile;
                } else {
                    downloadedFile = new File(result);
                }
                if (callback.isCancelled()) throw new IOException("Cancelled by user");
                // Saving to MediaStore is not cancellable once the copy begins.
                activeCallback = null;
                runOnUiThread(() -> {
                    cancelButton.setEnabled(false);
                    status("Saving to Android gallery ...");
                });
                Uri saved = copyToGallery(downloadedFile);
                runOnUiThread(() -> {
                    progressBar.setProgress(100);
                    status("Saved to Movies/InstaSaver");
                    log("Saved: " + saved);
                    Toast.makeText(this,
                        "Video saved. Tap My downloaded videos to watch.",
                        Toast.LENGTH_LONG).show();
                    complete();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (callback.isCancelled()) {
                        status("Download cancelled");
                        log("Cancelled by user");
                        complete();
                    } else {
                        fail(e.toString());
                    }
                });
            } finally {
                activeCallback = null;
                // Clean up both component streams and merged output, including on errors.
                if (downloadedFile != null && downloadedFile.isFile()) downloadedFile.delete();
                if (videoTrack != null && videoTrack.isFile()) videoTrack.delete();
                if (audioTrack != null && audioTrack.isFile()) audioTrack.delete();
                if (mergedFile != null && mergedFile.isFile()) mergedFile.delete();
            }
        });
    }

    /** Chaquopy exposes this Java object as an ordinary Python callback. */
    public final class ProgressCallback {
        private volatile boolean cancelled = false;

        public boolean isCancelled() { return cancelled; }

        void cancel() { cancelled = true; }

        public void report(double percent, String message) {
            if (cancelled) return;
            runOnUiThread(() -> {
                if (cancelled) return;
                progressBar.setProgress((int) Math.max(0, Math.min(99, percent)));
                status(message);
            });
        }
    }

    private Uri copyToGallery(File file) throws IOException {
        if (!file.isFile() || file.length() == 0) throw new IOException("Downloaded file missing or empty");
        String filename = file.getName();
        String ext = filename.toLowerCase(Locale.ROOT);
        String mime = ext.endsWith(".webm") ? "video/webm"
            : ext.endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/InstaSaver");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        ContentResolver resolver = getContentResolver();
        Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("MediaStore insertion failed");
        boolean success = false;
        try {
            try (FileInputStream in = new FileInputStream(file);
                 OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new IOException("Cannot open MediaStore output");
                byte[] buf = new byte[65536];
                int count;
                while ((count = in.read(buf)) != -1) out.write(buf, 0, count);
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(uri, ready, null, null) < 1) {
                throw new IOException("Cannot publish MediaStore file");
            }
            success = true;
            return uri;
        } finally {
            if (!success) resolver.delete(uri, null, null);
        }
    }

    private void fail(String problem) {
        status("Download failed — see log");
        log("ERROR: " + problem);
        complete();
    }

    private void complete() {
        downloading = false;
        activeCallback = null;
        cancelButton.setEnabled(false);
        downloadButton.setEnabled(true);
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }
}
