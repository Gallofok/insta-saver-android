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
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small standalone Android UI; Python yt-dlp runs only on a worker thread. */
public class MainActivity extends Activity {
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
            try {
                if (callback.isCancelled()) throw new IOException("Cancelled by user");
                if (!Python.isStarted()) Python.start(new AndroidPlatform(getApplicationContext()));
                PyObject module = Python.getInstance().getModule("downloader");
                PyObject value = module.callAttr("download_video", source, cache.getAbsolutePath(), callback);
                downloadedFile = new File(value.toString());
                // Cancellation is available during extraction, not while saving to MediaStore.
                activeCallback = null;
                if (callback.isCancelled()) throw new IOException("Cancelled by user");
                runOnUiThread(() -> {
                    cancelButton.setEnabled(false);
                    status("Saving to Android gallery ...");
                });
                Uri saved = copyToGallery(downloadedFile);
                runOnUiThread(() -> {
                    progressBar.setProgress(100);
                    status("Saved to Movies/InstaSaver");
                    log("Saved: " + saved);
                    Toast.makeText(this, "Video saved", Toast.LENGTH_LONG).show();
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
                // A failed gallery write should not leave a complete video in cache.
                if (downloadedFile != null && downloadedFile.isFile()) downloadedFile.delete();
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
