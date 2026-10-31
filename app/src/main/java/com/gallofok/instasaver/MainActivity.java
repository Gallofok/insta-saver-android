package com.gallofok.instasaver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Date;
import java.text.DateFormat;
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
    private LinearLayout recentDownloads;
    private final ExecutorService galleryWorker = Executors.newSingleThreadExecutor();
    private volatile int recentGeneration = 0;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        createUi();
        handleIncoming(getIntent());
    }

    @Override protected void onResume() {
        super.onResume();
        refreshRecentDownloads();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncoming(intent);
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    // v0.3.7: visual-only redesign. All download, Cookie and media workflows remain unchanged.
    private static final int BLUE = Color.rgb(19, 85, 216);
    private static final int DEEP_BLUE = Color.rgb(12, 40, 112);
    private static final int SOFT_TEXT = Color.rgb(81, 104, 143);
    private static final int BACKGROUND = Color.rgb(246, 250, 255);

    private GradientDrawable fill(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private GradientDrawable gradient(int first, int second, int radius) {
        GradientDrawable d = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[]{first, second});
        d.setCornerRadius(dp(radius));
        return d;
    }

    private TextView text(String label, int sp, int color, boolean strong) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (strong) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button roundedButton(String title, boolean primary) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(title);
        b.setTextSize(14);
        b.setTextColor(primary ? Color.WHITE : BLUE);
        b.setBackground(primary
            ? gradient(Color.rgb(36, 113, 245), Color.rgb(11, 62, 179), 17)
            : gradient(Color.rgb(230, 245, 255), Color.rgb(202, 230, 255), 17));
        b.setMinHeight(dp(52));
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setElevation(dp(1));
        return b;
    }

    private void createUi() {
        getWindow().setStatusBarColor(Color.rgb(236, 247, 255));
        getWindow().setNavigationBarColor(Color.rgb(238, 247, 255));
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(BACKGROUND);
        setContentView(shell);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(18));
        scroll.addView(content);

        // Branded blue header.
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(18), dp(12), dp(18));
        header.setBackground(gradient(Color.rgb(239, 250, 255),
            Color.rgb(208, 234, 255), 24));
        LinearLayout.LayoutParams headParams = new LinearLayout.LayoutParams(-1, -2);
        headParams.bottomMargin = dp(14);
        content.addView(header, headParams);

        TextView logo = text("↓", 27, Color.WHITE, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(gradient(Color.rgb(0, 186, 230),
            Color.rgb(15, 57, 174), 17));
        header.addView(logo, new LinearLayout.LayoutParams(dp(55), dp(55)));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setPadding(dp(11), 0, 0, 0);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        brand.addView(text("Insta Saver", 23, DEEP_BLUE, true));
        TextView tagline = text("Save Instagram Reels & YouTube Shorts", 12,
            SOFT_TEXT, false);
        tagline.setMaxLines(2);
        brand.addView(tagline);

        Button menu = new Button(this);
        menu.setAllCaps(false);
        menu.setText("⋮");
        menu.setTextSize(24);
        menu.setTextColor(DEEP_BLUE);
        menu.setBackground(fill(Color.TRANSPARENT, 16));
        header.addView(menu, new LinearLayout.LayoutParams(dp(42), dp(50)));
        menu.setOnClickListener(v -> openSettings());

        // Input and download actions.
        LinearLayout linkCard = new LinearLayout(this);
        linkCard.setOrientation(LinearLayout.VERTICAL);
        linkCard.setPadding(dp(14), dp(17), dp(14), dp(17));
        GradientDrawable whiteCard = fill(Color.WHITE, 24);
        whiteCard.setStroke(dp(1), Color.rgb(225, 238, 252));
        linkCard.setBackground(whiteCard);
        linkCard.setElevation(dp(2));
        LinearLayout.LayoutParams linkMargin = new LinearLayout.LayoutParams(-1, -2);
        linkMargin.bottomMargin = dp(16);
        content.addView(linkCard, linkMargin);

        urlInput = new EditText(this);
        urlInput.setHint("Paste Instagram Reel or YouTube Shorts link");
        urlInput.setHintTextColor(Color.rgb(100, 120, 149));
        urlInput.setTextColor(DEEP_BLUE);
        urlInput.setTextSize(14);
        urlInput.setSingleLine(false);
        urlInput.setMinLines(2);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable field = fill(Color.rgb(244, 249, 255), 14);
        field.setStroke(dp(1), Color.rgb(174, 197, 229));
        urlInput.setBackground(field);
        linkCard.addView(urlInput, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsMargin = new LinearLayout.LayoutParams(-1, -2);
        actionsMargin.topMargin = dp(12);
        linkCard.addView(actions, actionsMargin);

        Button paste = roundedButton("▣  Paste link", false);
        actions.addView(paste, new LinearLayout.LayoutParams(0, dp(51), 1));
        paste.setOnClickListener(v -> pasteClipboard());

        downloadButton = roundedButton("↓  Download video", true);
        LinearLayout.LayoutParams downloadMargin = new LinearLayout.LayoutParams(0, dp(51), 1);
        downloadMargin.leftMargin = dp(10);
        actions.addView(downloadButton, downloadMargin);
        downloadButton.setOnClickListener(v -> download());

        TextView platforms = text("SUPPORTED PLATFORMS", 11, SOFT_TEXT, false);
        platforms.setGravity(Gravity.CENTER);
        platforms.setPadding(0, dp(17), 0, dp(10));
        linkCard.addView(platforms);

        LinearLayout chips = new LinearLayout(this);
        chips.setGravity(Gravity.CENTER);
        linkCard.addView(chips, new LinearLayout.LayoutParams(-1, -2));
        String[] chipTitles = {"IG Reel", "IG Post", "YT Shorts"};
        for (int i = 0; i < chipTitles.length; i++) {
            TextView chip = text(chipTitles[i], 11, DEEP_BLUE, false);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(3), dp(11), dp(3), dp(11));
            chip.setBackground(fill(i == 2
                ? Color.rgb(235, 245, 255) : Color.rgb(226, 242, 255), 24));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) cp.leftMargin = dp(6);
            chips.addView(chip, cp);
        }

        // Three quick actions match the approved blue UI design.
        LinearLayout quickRow = new LinearLayout(this);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams quickMargins = new LinearLayout.LayoutParams(-1, -2);
        quickMargins.bottomMargin = dp(16);
        content.addView(quickRow, quickMargins);

        quickTile(quickRow, "▱", "My\ndownloads",
            0, Color.rgb(226, 244, 255), Color.rgb(180, 222, 255),
            v -> openDownloads());
        quickTile(quickRow, "◌", "Import\nInstagram\ncookies",
            1, Color.rgb(227, 253, 255), Color.rgb(187, 238, 250),
            v -> chooseInstagramCookies());
        quickTile(quickRow, "☷", "Copy\ndebug log",
            2, Color.rgb(235, 242, 255), Color.rgb(206, 224, 255),
            v -> copyDebugLog());

        // Reusable progress and cancellation controls. Status updates still use status().
        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(16), dp(15), dp(16), dp(14));
        GradientDrawable statusBg = gradient(
            Color.rgb(232, 250, 255), Color.rgb(214, 240, 255), 21);
        statusBg.setStroke(dp(1), Color.rgb(158, 221, 249));
        statusCard.setBackground(statusBg);
        LinearLayout.LayoutParams statusMargin = new LinearLayout.LayoutParams(-1, -2);
        statusMargin.bottomMargin = dp(18);
        content.addView(statusCard, statusMargin);

        statusText = text("Ready to download", 17, BLUE, true);
        statusCard.addView(statusText);
        TextView helper = text("Keep app open during download.", 12, SOFT_TEXT, false);
        LinearLayout.LayoutParams helperMargin = new LinearLayout.LayoutParams(-1, -2);
        helperMargin.topMargin = dp(3);
        statusCard.addView(helper, helperMargin);

        progressBar = new ProgressBar(this, null,
            android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(BLUE));
        LinearLayout.LayoutParams progressMargin = new LinearLayout.LayoutParams(-1, dp(9));
        progressMargin.topMargin = dp(13);
        statusCard.addView(progressBar, progressMargin);

        cancelButton = roundedButton("Cancel download", false);
        cancelButton.setEnabled(false);
        LinearLayout.LayoutParams cancelMargin = new LinearLayout.LayoutParams(-1, dp(40));
        cancelMargin.topMargin = dp(9);
        statusCard.addView(cancelButton, cancelMargin);
        cancelButton.setOnClickListener(v -> {
            ProgressCallback callback = activeCallback;
            if (callback == null) return;
            callback.cancel();
            cancelButton.setEnabled(false);
            status("Cancelling download ...");
        });

        TextView location = text("Saves to Movies/InstaSaver", 11, SOFT_TEXT, false);
        location.setPadding(0, dp(8), 0, 0);
        statusCard.addView(location);

        LinearLayout recentHeading = new LinearLayout(this);
        recentHeading.setOrientation(LinearLayout.HORIZONTAL);
        recentHeading.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(recentHeading, new LinearLayout.LayoutParams(-1, -2));
        recentHeading.addView(text("Recent downloads", 18, DEEP_BLUE, true),
            new LinearLayout.LayoutParams(0, -2, 1));
        Button seeAll = new Button(this);
        seeAll.setAllCaps(false);
        seeAll.setText("See all ›");
        seeAll.setTextSize(13);
        seeAll.setTextColor(BLUE);
        seeAll.setBackground(fill(Color.TRANSPARENT, 10));
        recentHeading.addView(seeAll);
        seeAll.setOnClickListener(v -> openDownloads());

        recentDownloads = new LinearLayout(this);
        recentDownloads.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams recentParams = new LinearLayout.LayoutParams(-1, -2);
        recentParams.topMargin = dp(6);
        content.addView(recentDownloads, recentParams);
        recentDownloads.addView(text("Your saved videos appear here.", 13,
            SOFT_TEXT, false));

        // Debug log is kept in memory, and remains accessible via Copy / Settings.
        logText = new TextView(this);
        logText.setTextIsSelectable(true);
        log("Initialized. Public Instagram posts and YouTube Shorts only. Keep app open while downloading.");

        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setPadding(dp(16), dp(6), dp(16), dp(7));
        bottomBar.setBackground(gradient(Color.rgb(242, 249, 255),
            Color.rgb(231, 243, 255), 22));
        shell.addView(bottomBar, new LinearLayout.LayoutParams(-1, -2));

        bottomNav(bottomBar, "⌂\nHome", true, v -> scroll.smoothScrollTo(0, 0));
        bottomNav(bottomBar, "↓\nDownloads", false, v -> openDownloads());
        bottomNav(bottomBar, "⚙\nSettings", false, v -> openSettings());
    }

    private void quickTile(LinearLayout parent, String glyph, String name,
            int position, int from, int to, View.OnClickListener click) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(4), dp(12), dp(4), dp(12));
        tile.setBackground(gradient(from, to, 19));
        tile.setMinimumHeight(dp(112));
        LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, dp(112), 1);
        if (position > 0) tileParams.leftMargin = dp(8);
        parent.addView(tile, tileParams);
        TextView symbol = text(glyph, 29, BLUE, true);
        symbol.setGravity(Gravity.CENTER);
        tile.addView(symbol);
        TextView label = text(name, 12, DEEP_BLUE, true);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(5);
        tile.addView(label, lp);
        tile.setOnClickListener(click);
    }

    private void bottomNav(LinearLayout parent, String name,
            boolean selected, View.OnClickListener action) {
        TextView item = text(name, 12, selected ? BLUE : SOFT_TEXT, selected);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(5), dp(10), dp(5), dp(10));
        item.setBackground(selected ? fill(Color.rgb(202, 230, 255), 22)
            : fill(Color.TRANSPARENT, 22));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1);
        parent.addView(item, lp);
        item.setOnClickListener(action);
    }

    private void openDownloads() {
        startActivity(new Intent(this, DownloadsActivity.class));
    }

    private void chooseInstagramCookies() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        startActivityForResult(pick, PICK_INSTAGRAM_COOKIES);
    }

    private void removeInstagramCookies() {
        File cookies = instagramCookiesFile();
        boolean removed = !cookies.exists() || cookies.delete();
        status(removed ? "Imported Instagram session removed" : "Could not remove Instagram session");
        log(removed ? "Instagram cookies removed locally" : "Cannot delete imported Instagram cookies");
    }

    private void copyDebugLog() {
        ClipboardManager clipboard = (ClipboardManager)
            getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Insta Saver log", logText.getText()));
        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
    }

    private void openSettings() {
        new AlertDialog.Builder(this)
            .setTitle("Insta Saver · Settings")
            .setItems(new String[] {
                "Import Instagram cookies.txt",
                "Remove Instagram login session",
                "Copy debug log",
                "View debug log",
                "My downloaded videos"
            }, (dialog, index) -> {
                switch (index) {
                    case 0: chooseInstagramCookies(); break;
                    case 1:
                        new AlertDialog.Builder(this)
                            .setTitle("Remove Instagram session?")
                            .setMessage("This deletes the imported Cookie file from this app only.")
                            .setPositiveButton("Remove", (confirm, which) -> removeInstagramCookies())
                            .setNegativeButton("Cancel", null)
                            .show();
                        break;
                    case 2: copyDebugLog(); break;
                    case 3:
                        TextView viewer = text(logText.getText().toString(), 12, DEEP_BLUE, false);
                        viewer.setTextIsSelectable(true);
                        viewer.setPadding(dp(16), dp(14), dp(16), dp(14));
                        ScrollView scroller = new ScrollView(this);
                        scroller.addView(viewer);
                        new AlertDialog.Builder(this)
                            .setTitle("Debug log")
                            .setView(scroller)
                            .setPositiveButton("Copy", (copyDialog, which) -> copyDebugLog())
                            .setNegativeButton("Close", null)
                            .show();
                        break;
                    case 4: openDownloads(); break;
                    default: break;
                }
            })
            .setNegativeButton("Close", null)
            .show();
    }

    private static final class RecentVideo {
        Uri uri;
        String filename;
        long duration;
        long size;
        long timestamp;
        Bitmap thumb;
    }

    private void refreshRecentDownloads() {
        if (recentDownloads == null) return;
        final int generation = ++recentGeneration;
        galleryWorker.execute(() -> {
            List<RecentVideo> videos = new ArrayList<>();
            try {
                String[] cols = {
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.SIZE
                };
                String where = "(" + MediaStore.Video.Media.RELATIVE_PATH
                    + " = ? OR " + MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?)";
                try (Cursor cur = getContentResolver().query(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        cols, where,
                        new String[]{"Movies/InstaSaver", "Movies/InstaSaver/%"},
                        MediaStore.Video.Media.DATE_ADDED + " DESC")) {
                    if (cur != null) {
                        int id = cur.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                        int filename = cur.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
                        int duration = cur.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
                        int timestamp = cur.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
                        int size = cur.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
                        while (cur.moveToNext() && videos.size() < 3) {
                            RecentVideo item = new RecentVideo();
                            item.uri = ContentUris.withAppendedId(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cur.getLong(id));
                            item.filename = cur.getString(filename);
                            item.duration = cur.isNull(duration) ? 0 : cur.getLong(duration);
                            item.timestamp = cur.isNull(timestamp) ? 0 : cur.getLong(timestamp);
                            item.size = cur.isNull(size) ? 0 : cur.getLong(size);
                            try {
                                item.thumb = getContentResolver().loadThumbnail(
                                    item.uri, new Size(180, 130), null);
                            } catch (Exception ignored) {}
                            videos.add(item);
                        }
                    }
                }
            } catch (Exception ignored) {
                // Keep the homepage functional even when video permission is limited.
            }
            runOnUiThread(() -> {
                if (generation != recentGeneration || isFinishing() || isDestroyed()) return;
                recentDownloads.removeAllViews();
                if (videos.isEmpty()) {
                    TextView message = text(
                        "No recent videos visible. Open Downloads to manage video access.",
                        12, SOFT_TEXT, false);
                    message.setPadding(dp(8), dp(16), dp(8), dp(16));
                    recentDownloads.addView(message);
                    message.setOnClickListener(v -> openDownloads());
                } else {
                    for (RecentVideo video : videos) addRecentVideo(video);
                }
            });
        });
    }

    private void addRecentVideo(RecentVideo video) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable border = fill(Color.WHITE, 17);
        border.setStroke(dp(1), Color.rgb(220, 234, 252));
        card.setBackground(border);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(9);
        recentDownloads.addView(card, p);

        ImageView thumb = new ImageView(this);
        thumb.setBackground(fill(Color.rgb(208, 229, 250), 11));
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (video.thumb != null) thumb.setImageBitmap(video.thumb);
        else thumb.setImageResource(android.R.drawable.ic_media_play);
        thumb.setClipToOutline(true);
        card.addView(thumb, new LinearLayout.LayoutParams(dp(104), dp(77)));

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setPadding(dp(11), 0, dp(3), 0);
        card.addView(meta, new LinearLayout.LayoutParams(0, -2, 1));
        TextView filename = text(
            video.filename == null ? "Saved video" : video.filename,
            12, DEEP_BLUE, true);
        filename.setMaxLines(1);
        filename.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        meta.addView(filename);
        boolean isShorts = video.filename != null && video.filename.startsWith("shorts_");
        TextView origin = text(isShorts ? "YouTube Shorts" : "Instagram",
            11, SOFT_TEXT, false);
        meta.addView(origin);
        String duration = video.duration > 0
            ? String.format(Locale.ROOT, "%d:%02d",
                video.duration / 60000, (video.duration / 1000) % 60)
            : "--:--";
        String detail = duration + " · " + (video.size / (1024 * 1024)) + " MB";
        meta.addView(text(detail, 11, SOFT_TEXT, false));
        card.setOnClickListener(v -> {
            Intent play = new Intent(this, VideoPlayerActivity.class);
            play.setData(video.uri);
            play.putExtra(VideoPlayerActivity.EXTRA_TITLE, video.filename);
            startActivity(play);
        });
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
        ++recentGeneration;
        galleryWorker.shutdownNow();
        super.onDestroy();
    }
}
