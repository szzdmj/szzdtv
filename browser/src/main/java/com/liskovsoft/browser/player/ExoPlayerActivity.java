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
        mPlayer = new ExoPlayer.Builder(this).build();
        mPlayerView.setPlayer(mPlayer);
        mPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);

        Intent intent = getIntent();
        if (intent == null) {
            Toast.makeText(this, "No media to play", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        String url = intent.getStringExtra(EXTRA_VIDEO_URL);
        if (url == null || url.isEmpty()) {
            Toast.makeText(this, "No media URL provided", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Optional title
        String title = intent.getStringExtra(EXTRA_TITLE);
        if (title != null && !title.isEmpty()) setTitle(title);

        // Optional headers (Serializable)
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) intent.getSerializableExtra(EXTRA_HEADERS);

        try {
            Uri uri = Uri.parse(url);

            // Build HttpDataSource.Factory with headers if provided
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory();
            if (headers != null && !headers.isEmpty()) {
                httpFactory.setDefaultRequestProperties(headers);
                Log.i(TAG, "Applied " + headers.size() + " request headers");
            }

            // Wrap into DefaultDataSource.Factory so ExoPlayer can use either file/cache/http uniformly
            DefaultDataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(this, httpFactory);

            MediaItem mediaItem = new MediaItem.Builder().setUri(uri).build();

            // Use media item and dataSourceFactory to prepare player
            mPlayer.setMediaItem(mediaItem);
            mPlayer.setMediaSource(new com.google.android.exoplayer2.source.ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem));
            mPlayer.prepare();
            mPlayer.play();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start playback", t);
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

    // Remote / DPAD key handling: forward to controller where appropriate
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mPlayerView != null && mPlayerView.dispatchKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    // Optional: expose hooks to integrate MediaSession / Notifications later
}
