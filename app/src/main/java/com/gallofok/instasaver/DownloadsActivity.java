package com.gallofok.instasaver;

import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-app gallery of the videos this app saved to Movies/InstaSaver using MediaStore.
 * It deliberately avoids broad photo/video permissions: on Android 10+ an app can
 * access MediaStore entries that it created. Reinstalled apps may lose ownership.
 */
public final class DownloadsActivity extends Activity {
    private static final int MAX_ITEMS = 80;
    private static final String DOWNLOADS_PATH = "Movies/InstaSaver/";
    private final ExecutorService diskWorker = Executors.newSingleThreadExecutor();
    private LinearLayout listRoot;
    private TextView summary;
    private volatile int loadGeneration = 0;

    private static final class SavedVideo {
        final Uri uri;
        final String name;
        final long durationMs;
        final long addedSeconds;
        ImageView preview;

        SavedVideo(Uri uri, String name, long durationMs, long addedSeconds) {
            this.uri = uri;
            this.name = name;
            this.durationMs = durationMs;
            this.addedSeconds = addedSeconds;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(249, 250, 252));
        root.setPadding(dp(12), dp(18), dp(12), dp(8));
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("My downloaded videos");
        title.setTextSize(22);
        title.setTextColor(Color.rgb(32, 45, 79));
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        Button refresh = new Button(this);
        refresh.setText("Refresh");
        header.addView(refresh);
        refresh.setOnClickListener(v -> refreshGallery());

        summary = new TextView(this);
        summary.setTextColor(Color.DKGRAY);
        summary.setTextSize(13);
        summary.setPadding(dp(4), dp(4), 0, dp(10));
        root.addView(summary);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        listRoot = new LinearLayout(this);
        listRoot.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listRoot);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshGallery();
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private void refreshGallery() {
        int token = ++loadGeneration;
        summary.setText("Loading saved videos ...");
        listRoot.removeAllViews();
        diskWorker.execute(() -> {
            List<SavedVideo> items = new ArrayList<>();
            String problem = null;
            try {
                // No read permission is necessary for media created by this app
                // on supported Android 10+ versions.
                String[] projection = {
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.DATE_ADDED
                };
                String selection = MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?";
                String[] args = { DOWNLOADS_PATH + "%" };
                try (Cursor cursor = getContentResolver().query(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        projection, selection, args,
                        MediaStore.Video.Media.DATE_ADDED + " DESC")) {
                    if (cursor == null) throw new IllegalStateException("Media library unavailable");
                    int idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                    int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
                    int durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
                    int addedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
                    while (cursor.moveToNext() && items.size() < MAX_ITEMS) {
                        long id = cursor.getLong(idCol);
                        Uri uri = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                        String name = cursor.getString(nameCol);
                        items.add(new SavedVideo(uri, name == null ? "Untitled video" : name,
                            cursor.isNull(durationCol) ? 0 : cursor.getLong(durationCol),
                            cursor.isNull(addedCol) ? 0 : cursor.getLong(addedCol)));
                    }
                }
            } catch (Exception exception) {
                // Do not emit app-private filenames, content URIs, or stack traces.
                problem = exception instanceof SecurityException
                        ? "Android denied access to these videos. This version lists media saved by Insta Saver itself."
                        : "Could not read Android's video library.";
            }

            final String error = problem;
            runOnUiThread(() -> {
                if (token != loadGeneration || isFinishing() || isDestroyed()) return;
                if (error != null) {
                    summary.setText(error);
                    addMessage("Try downloading a new video, then reopen My downloads.");
                    return;
                }
                summary.setText(items.size() == MAX_ITEMS
                    ? "Showing latest " + MAX_ITEMS + " videos in Movies/InstaSaver"
                    : items.size() + " videos in Movies/InstaSaver");
                if (items.isEmpty()) {
                    addMessage("No downloaded videos found. Videos saved by Insta Saver will appear here.");
                    return;
                }
                populateCards(items);
                // Run thumbnail extraction off the UI thread.
                diskWorker.execute(() -> loadThumbnails(items, token));
            });
        });
    }

    private void addMessage(String message) {
        TextView empty = new TextView(this);
        empty.setText(message);
        empty.setTextSize(15);
        empty.setTextColor(Color.DKGRAY);
        empty.setPadding(dp(10), dp(24), dp(10), dp(12));
        listRoot.addView(empty);
    }

    private void populateCards(List<SavedVideo> items) {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        listRoot.addView(grid, new LinearLayout.LayoutParams(-1, -2));

        DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        for (SavedVideo item : items) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(Color.WHITE);
            card.setPadding(dp(6), dp(6), dp(6), dp(10));
            card.setClickable(true);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f));
            params.width = 0;
            params.height = -2;
            params.setMargins(dp(4), dp(4), dp(4), dp(8));
            grid.addView(card, params);

            ImageView thumbnail = new ImageView(this);
            thumbnail.setBackgroundColor(Color.rgb(225, 229, 235));
            thumbnail.setImageResource(android.R.drawable.ic_media_play);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            card.addView(thumbnail, new LinearLayout.LayoutParams(-1, dp(150)));
            item.preview = thumbnail;

            TextView name = new TextView(this);
            name.setText(item.name);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            name.setTextColor(Color.rgb(37, 50, 70));
            name.setTextSize(12);
            name.setPadding(0, dp(7), 0, 0);
            card.addView(name);

            TextView details = new TextView(this);
            String duration = item.durationMs > 0
                ? String.format(Locale.ROOT, "%d:%02d",
                    item.durationMs / 60000, (item.durationMs / 1000) % 60)
                : "--:--";
            String date = item.addedSeconds > 0
                ? dateFormat.format(new Date(item.addedSeconds * 1000))
                : "Unknown date";
            details.setText(duration + " · " + date);
            details.setTextSize(11);
            details.setTextColor(Color.GRAY);
            card.addView(details);

            card.setOnClickListener(v -> {
                Intent play = new Intent(this, VideoPlayerActivity.class);
                play.setData(item.uri);
                play.putExtra(VideoPlayerActivity.EXTRA_TITLE, item.name);
                startActivity(play);
            });
        }
    }

    private void loadThumbnails(List<SavedVideo> items, int token) {
        for (SavedVideo item : items) {
            if (token != loadGeneration || Thread.currentThread().isInterrupted()) return;
            Bitmap bitmap;
            try {
                bitmap = getContentResolver().loadThumbnail(
                    item.uri, new Size(320, 240), null);
            } catch (Exception ignored) {
                // Unsupported or unavailable thumbnail: keep play icon placeholder.
                continue;
            }
            final Bitmap ready = bitmap;
            runOnUiThread(() -> {
                if (token == loadGeneration && !isFinishing() && !isDestroyed()
                        && item.preview != null) {
                    item.preview.setImageBitmap(ready);
                    item.preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
                } else if (!ready.isRecycled()) {
                    ready.recycle();
                }
            });
        }
    }

    @Override protected void onDestroy() {
        ++loadGeneration;
        diskWorker.shutdownNow();
        super.onDestroy();
    }
}
