package com.liskovsoft.browser.player;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
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
 * Enhanced ExoPlayerActivity:
 * - logs lifecycle and extras to CrashLogger for verification
 * - forces landscape orientation on start for true horizontal playback
 * - supports optional headers passed as Serializable HashMap under key "extra_headers"
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
            i.putExtra(EXTRA_HEADERS, new HashMap<>(headers));
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Log entry and intent summary
        try {
            String instMsg = "EXOPLAYER_ONCREATE: package=" + getPackageName() + " intentExtras=" + getIntent().getExtras();
            Log.i(TAG, instMsg);
            try { com.szzdmj.nanohttpd.CrashLogger.i(instMsg); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}

        // Force landscape for immersive horizontal playback
        try {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            String orientMsg = "EXOPLAYER_ONCREATE: requested landscape orientation";
            Log.i(TAG, orientMsg);
            try { com.szzdmj.nanohttpd.CrashLogger.i(orientMsg); } catch (Throwable ignored) {}
        } catch (Throwable e) {
            Log.w(TAG, "Failed to set orientation", e);
            try { com.szzdmj.nanohttpd.CrashLogger.w("Failed to set orientation: " + e, null); } catch (Throwable ignored) {}
        }

        // Keep screen on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mPlayerView = new PlayerView(this);
        mPlayerView.setLayoutParams(new PlayerView.LayoutParams(
                PlayerView.LayoutParams.MATCH_PARENT, PlayerView.LayoutParams.MATCH_PARENT));
        mPlayerView.setUseController(true);
        mPlayerView.setControllerShowTimeoutMs(3000);
        setContentView(mPlayerView);

        enterImmersiveMode();
        try { com.szzdmj.nanohttpd.CrashLogger.i("EXOPLAYER_ONCREATE: enterImmersiveMode called"); } catch (Throwable ignored) {}
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

    @SuppressWarnings("unchecked")
    private void initPlayer() {
        try { com.szzdmj.nanohttpd.CrashLogger.i("EXOPLAYER_INIT: start"); } catch (Throwable ignored) {}

        mPlayer = new ExoPlayer.Builder(this).build();
        mPlayerView.setPlayer(mPlayer);
        mPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);

        Intent intent = getIntent();
        if (intent == null) {
            try { com.szzdmj.nanohttpd.CrashLogger.i("EXOPLAYER_INIT: no intent -> finish"); } catch (Throwable ignored) {}
            finish();
            return;
        }
        String url = intent.getStringExtra(EXTRA_VIDEO_URL);
        String title = intent.getStringExtra(EXTRA_TITLE);
        Serializable serHeaders = intent.getSerializableExtra(EXTRA_HEADERS);
        Map<String, String> headers = null;
        if (serHeaders instanceof Map) {
            headers = (Map<String, String>) serHeaders;
        }

        try {
            String extraMsg = "EXOPLAYER_INIT_EXTRAS: url=" + url + " title=" + title + " headers_present=" + (headers != null);
            Log.i(TAG, extraMsg);
            try { com.szzdmj.nanohttpd.CrashLogger.i(extraMsg); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}

        if (url == null || url.isEmpty()) {
            Toast.makeText(this, "No media URL provided", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        try {
            Uri uri = Uri.parse(url);

            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory();
            if (headers != null && !headers.isEmpty()) {
                httpFactory.setDefaultRequestProperties(headers);
                try { com.szzdmj.nanohttpd.CrashLogger.i("EXOPLAYER_INIT: applying headers count=" + headers.size()); } catch (Throwable ignored) {}
            }

            DefaultDataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(this, httpFactory);

            MediaItem mediaItem = MediaItem.fromUri(uri);

            mPlayer.setMediaItem(mediaItem);
            mPlayer.setMediaSource(new com.google.android.exoplayer2.source.ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem));
            mPlayer.prepare();
            mPlayer.play();

            try { com.szzdmj.nanohttpd.CrashLogger.i("EXOPLAYER_INIT: prepare/play called for " + url); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start playback", t);
            try { com.szzdmj.nanohttpd.CrashLogger.w("EXOPLAYER_INIT_ERR: " + t, null); } catch (Throwable ignored) {}
            Toast.makeText(this, "Playback error", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void releasePlayer() {
        if (mPlayer != null) {
            try { mPlayer.stop(); } catch (Throwable ignored) {}
            mPlayer.release();
            mPlayer = null;
            if (mPlayerView != null) mPlayerView.setPlayer(null);
        }
    }

    private void enterImmersiveMode() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
        mHandler.removeCallbacks(mReenterImmersive);
        mHandler.postDelayed(mReenterImmersive, 2000);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mPlayerView != null && mPlayerView.dispatchKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }
}
