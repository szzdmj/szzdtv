package com.liskovsoft.browser.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Immersive ExoPlayerActivity with optional headers support.
 *
 * Start with:
 *   ExoPlayerActivity.start(context, url, title, headersMap);
 *
 * headersMap may be null.
 */
public class ExoPlayerActivity extends AppCompatActivity {
    private static final String TAG = "ExoPlayerActivity";

    public static final String EXTRA_VIDEO_URL = "extra_video_url";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_HEADERS = "extra_headers";

    private PlayerView mPlayerView;
    private ExoPlayer mPlayer;
    private final Handler mHandler = new Handler();
    private final Runnable mReenterImmersive = this::enterImmersiveMode;

    public static void start(Context ctx, String url, @Nullable String title, @Nullable Map<String, String> headers) {
        Intent i = new Intent(ctx, ExoPlayerActivity.class);
        i.putExtra(EXTRA_VIDEO_URL, url);
        if (title != null) i.putExtra(EXTRA_TITLE, title);
        if (headers != null && !headers.isEmpty()) {
            // HashMap implements Serializable
            i.putExtra(EXTRA_HEADERS, new HashMap<>(headers));
        }
        ctx.startActivity(i);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

    // Log start of activity and received intent extras
    try {
        String instMsg = "EXOPLAYER_ONCREATE: package=" + getPackageName() + " intent=" + getIntent();
        android.util.Log.i("ExoPlayerActivity", instMsg);
        try { com.szzdmj.nanohttpd.CrashLogger.i(instMsg); } catch (Throwable ignored) {}
    } catch (Throwable ignored) {}

        // Keep screen on while playing
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mPlayerView = new PlayerView(this);
        mPlayerView.setLayoutParams(new PlayerView.LayoutParams(
                PlayerView.LayoutParams.MATCH_PARENT, PlayerView.LayoutParams.MATCH_PARENT));
        mPlayerView.setUseController(true);
        mPlayerView.setControllerShowTimeoutMs(3000);
        setContentView(mPlayerView);

        // Enter immersive
        enterImmersiveMode();
       try {
        com.szzdmj.nanohttpd.CrashLogger.i("EXOPLAYER_ONCREATE: enterImmersiveMode called");
    } catch (Throwable ignored) {}
 }

    @Override
    protected void onStart() {
        super.onStart();
        if (mPlayer == null) initPlayer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.postDelayed(mReenterImmersive, 200);
        if (mPlayer != null) mPlayer.play();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mPlayer != null) mPlayer.pause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        releasePlayer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releasePlayer();
        mHandler.removeCallbacks(mReenterImmersive);
    }

    private void initPlayer() {
    try {
        com.szzdmj.nanohttpd.CrashLogger.i("EXOPLAYER_INIT: initPlayer start");
    } catch (Throwable ignored) {}

        mPlayer = new ExoPlayer.Builder(this).build();
        mPlayerView.setPlayer(mPlayer);
        mPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);

        Intent intent = getIntent();
        if (intent == null) {
        com.szzdmj.nanohttpd.CrashLogger.i("EXOPLAYER_INIT: no intent -> finish");
            return;
        }

        String url = intent.getStringExtra(EXTRA_VIDEO_URL);
        String title = intent.getStringExtra(EXTRA_TITLE);

    // Log received extras
        try {
        String extraMsg = "EXOPLAYER_INIT_EXTRAS: url=" + url + " title=" + title
                + " headers_present=" + (intent.getSerializableExtra(EXTRA_HEADERS) != null);
        android.util.Log.i("ExoPlayerActivity", extraMsg);
        try { com.szzdmj.nanohttpd.CrashLogger.i(extraMsg); } catch (Throwable ignored) {}
    } catch (Throwable ignored) {}

    // ... existing data source factory creation ...
    try {
            if (headers != null && !headers.isEmpty()) {
            com.szzdmj.nanohttpd.CrashLogger.i("EXOPLAYER_INIT: applying headers count=" + headers.size());
    }
    } catch (Throwable ignored) {}

    // After prepare/play
    try {
        com.szzdmj.nanohttpd.CrashLogger.i("EXOPLAYER_INIT: prepare/play called for " + url);
    } catch (Throwable ignored) {}
}
