package com.liskovsoft.browser.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.PlayerView;

/**
 * Minimal ExoPlayer activity with immersive (fullscreen) playback.
 *
 * - Keeps the rest of the app module unchanged.
 * - When started with an intent containing EXTRA_VIDEO_URL, enters immersive sticky fullscreen and plays media.
 * - Handles audio-only and video URIs.
 *
 * Usage:
 *   Intent i = new Intent(context, ExoPlayerActivity.class);
 *   i.putExtra(ExoPlayerActivity.EXTRA_VIDEO_URL, "https://.../file.m4a");
 *   context.startActivity(i);
 */
public class ExoPlayerActivity extends AppCompatActivity {
    public static final String EXTRA_VIDEO_URL = "extra_video_url";
    public static final String EXTRA_TITLE = "extra_title";

    private PlayerView mPlayerView;
    private ExoPlayer mPlayer;
    private final Handler mHandler = new Handler();

    // Runnable to re-apply immersive mode (useful if UI changes cause system bars to show briefly)
    private final Runnable mReenterImmersive = this::enterImmersiveMode;

    public static void start(Context ctx, String url, @Nullable String title) {
        Intent i = new Intent(ctx, ExoPlayerActivity.class);
        i.putExtra(EXTRA_VIDEO_URL, url);
        if (title != null) i.putExtra(EXTRA_TITLE, title);
        ctx.startActivity(i);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen on while playing
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Use a very small XML layout (or create programmatically)
        // Expect layout has a PlayerView with id player_view.
        // If you don't have a layout file: create res/layout/activity_exo_player.xml with a PlayerView that matches this id.
        setContentView(createOrGetLayout());

        mPlayerView = findViewById(getPlayerViewId());

        // Start in immersive mode
        enterImmersiveMode();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Initialize player
        if (mPlayer == null) initPlayer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-enter immersive on resume (delayed to allow system to settle)
        mHandler.postDelayed(mReenterImmersive, 200);
        if (mPlayer != null) mPlayer.play();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mPlayer != null) mPlayer.pause();
        // keep the player instance to resume quickly
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
        // Build ExoPlayer
        mPlayer = new ExoPlayer.Builder(this).build();
        mPlayerView.setPlayer(mPlayer);
        mPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);

        // Get URL from intent
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

        // Prepare media item
        Uri uri = Uri.parse(url);
        MediaItem mediaItem = MediaItem.fromUri(uri);
        mPlayer.setMediaItem(mediaItem);

        // Optional: use title
        String title = intent.getStringExtra(EXTRA_TITLE);
        if (title != null && !title.isEmpty()) {
            setTitle(title);
        }

        mPlayer.prepare();
        mPlayer.play();
    }

    private void releasePlayer() {
        if (mPlayer != null) {
            try {
                mPlayer.stop();
            } catch (Throwable ignored) {}
            mPlayer.release();
            mPlayer = null;
            if (mPlayerView != null) mPlayerView.setPlayer(null);
        }
    }

    // Enter immersive sticky mode to hide system bars for an "immersive" playback experience.
    private void enterImmersiveMode() {
        View decor = getWindow().getDecorView();
        // For Android R and above, use WindowInsets API for more robust behavior if desired.
        // Use legacy flags for broad compatibility.
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
        // Re-apply after a short delay in case system UI pops up
        mHandler.removeCallbacks(mReenterImmersive);
        mHandler.postDelayed(mReenterImmersive, 2000);
    }

    // Helper: create a minimal layout programmatically if no layout resource is available.
    // This keeps the change localized to this file (no layout changes required).
    private int createOrGetLayout() {
        // We try to find an existing layout id named activity_exo_player; if not present, create a PlayerView programmatically.
        // Returning a resource id is required by setContentView(int), but to avoid requiring resource edits we will set content view programmatically.
        // So instead of returning a layout id, we will build the view tree and setContentView(View) here.
        // For clarity and simplicity: build the PlayerView and set as content view now.
        PlayerView pv = new PlayerView(this);
        pv.setLayoutParams(new PlayerView.LayoutParams(
                PlayerView.LayoutParams.MATCH_PARENT, PlayerView.LayoutParams.MATCH_PARENT));
        pv.setUseController(true);
        // make controller show on touch and then fade
        pv.setControllerShowTimeoutMs(3000);
        // id is optional, but set one for findViewById later
        pv.setId(getPlayerViewId());
        setContentView(pv);
        return 0; // not used
    }

    // Choose a deterministic ID for the PlayerView (avoid resource dependency)
    private int getPlayerViewId() {
        // use hashcode of class name as id (positive)
        return Math.abs("ExoPlayerActivityPlayerView".hashCode());
    }
}
