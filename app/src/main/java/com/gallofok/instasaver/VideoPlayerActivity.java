package com.gallofok.instasaver;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

/** Plays videos already saved in our Android MediaStore folder. */
public final class VideoPlayerActivity extends Activity {
    public static final String EXTRA_TITLE = "video_title";
    private VideoView videoView;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Uri uri = getIntent().getData();
        if (uri == null || !"content".equals(uri.getScheme())) {
            finish();
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(12, 18, 12, 12);
        setContentView(root);

        TextView title = new TextView(this);
        title.setText(getIntent().getStringExtra(EXTRA_TITLE));
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setMaxLines(2);
        title.setPadding(12, 8, 12, 12);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        videoView = new VideoView(this);
        LinearLayout.LayoutParams playerParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        playerParams.gravity = Gravity.CENTER;
        root.addView(videoView, playerParams);
        MediaController controller = new MediaController(this);
        controller.setAnchorView(videoView);
        videoView.setMediaController(controller);
        videoView.setOnPreparedListener(player -> {
            videoView.start();
            controller.show(3000);
        });
        videoView.setOnErrorListener((player, what, extra) -> {
            title.setText("Cannot play video. Try opening it from the system Gallery.");
            return true;
        });
        videoView.setVideoURI(uri);
        videoView.requestFocus();
    }

    @Override protected void onPause() {
        if (videoView != null && videoView.isPlaying()) videoView.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (videoView != null) videoView.stopPlayback();
        super.onDestroy();
    }
}
