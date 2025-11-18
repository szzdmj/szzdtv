package com.liskovsoft.smartyoutubetv.player;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.io.File;

/**
 * PlayerActivity with defensive layout inflation fallback.
 *
 * Replaces direct setContentView(...) with try/catch around inflation. If inflation fails
 * (InflateException) we write the stack to external log and create a minimal programmatic
 * fallback layout containing a PlayerView so the activity can continue for debugging.
 */
public class PlayerActivity extends AppCompatActivity {
    public static final String TAG = "PlayerActivity";

    private PlayerView playerView;
    private ExoPlayer player;
    private TextView fileNameTv;
    private ImageButton volUpBtn;
    private ImageButton volDownBtn;
    private ImageButton brightUpBtn;
    private ImageButton brightDownBtn;

    private AudioManager audioManager;
    private float brightnessStep = 0.1f;

    public static Intent createIntent(Context ctx, Uri uri) {
        Intent i = new Intent(ctx, PlayerActivity.class);
        i.setAction(Intent.ACTION_VIEW);
        i.setData(uri);
        return i;
    }

    @Override
protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Defensive inflation: try normal layout inflation, on InflateException fall back to
        // a programmatic minimal layout to allow continued execution and capture diagnostics.
        try {
            setContentView(com.liskovsoft.smartyoutubetv.R.layout.activity_player);

            // bind views (may be null if fallback branch is used)
            playerView = findViewById(com.liskovsoft.smartyoutubetv.R.id.player_view);
            fileNameTv = findViewById(com.liskovsoft.smartyoutubetv.R.id.tv_filename);
            volUpBtn = findViewById(com.liskovsoft.smartyoutubetv.R.id.btn_vol_up);
            volDownBtn = findViewById(com.liskovsoft.smartyoutubetv.R.id.btn_vol_down);
            brightUpBtn = findViewById(com.liskovsoft.smartyoutubetv.R.id.btn_bright_up);
            brightDownBtn = findViewById(com.liskovsoft.smartyoutubetv.R.id.btn_bright_down);
        } catch (android.view.InflateException ie) {
            // Log, persist and recover with fallback layout
            Log.e(TAG, "setContentView failed", ie);
            writeCrashLog(ie);
            createFallbackLayout();
        }

        // Initialize audio manager and listeners defensively (null-checks)
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        if (volUpBtn != null) volUpBtn.setOnClickListener(v -> adjustVolume(true));
        if (volDownBtn != null) volDownBtn.setOnClickListener(v -> adjustVolume(false));
        if (brightUpBtn != null) brightUpBtn.setOnClickListener(v -> adjustBrightness(true));
        if (brightDownBtn != null) brightDownBtn.setOnClickListener(v -> adjustBrightness(false));

        // Handle Intent (ACTION_VIEW)
        try {
            Intent intent = getIntent();
    if (intent != null) {
        boolean autoFs = intent.getBooleanExtra("auto_fullscreen", false);
        boolean autoPlay = intent.getBooleanExtra("auto_play", false);
        Uri videoUri = intent.getData(); // 也可能是 intent.getStringExtra("video_url")
        if (autoFs) {
            // 进入沉浸式、横屏模式
        try {
                // 强制横屏（如果你希望）
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            } catch (Throwable ignored) {}
            // 隐藏系统 UI（沉浸式）
            decorViewHideSystemUI();
        }
        // 如果传了 URL 且 autoPlay 为 true，开始播放
        if (videoUri != null && autoPlay) {
            // 假设播放器有 play(Uri) 方法，调用开始播放
            startPlayback(videoUri.toString());
    }
        }
    }

private void decorViewHideSystemUI() {
    final android.view.View decorView = getWindow().getDecorView();
    int flags = android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
    decorView.setSystemUiVisibility(flags);
    }

    private void adjustBrightness(boolean increase) {
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            float cur = lp.screenBrightness;
            if (cur < 0f) { // means system default; try to read system brightness (0..255)
                try {
                    int sys = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
                    cur = sys / 255f;
                } catch (Exception e) {
                    cur = 0.5f;
                }
            }
            float next = increase ? Math.min(1f, cur + brightnessStep) : Math.max(0f, cur - brightnessStep);
            lp.screenBrightness = next;
            getWindow().setAttributes(lp);
        } catch (Exception e) {
            Toast.makeText(this, "Can't change brightness: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // 将异常写入外部文件，返回路径（便于在无 adb 时用文件管理器取出）
    private String writeCrashLog(Throwable t) {
        try {
            java.io.File dir = getExternalFilesDir("logs");
            if (dir == null) return null;
            if (!dir.exists()) dir.mkdirs();
            String name = "crash-" + System.currentTimeMillis() + ".log";
            java.io.File f = new java.io.File(dir, name);
            java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(f));
            t.printStackTrace(pw);
            pw.flush();
            pw.close();
            return f.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Failed to write crash log", e);
            return null;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Player is initialized in onCreate when intent present. In other setups you could init here.
    }

    @Override
    protected void onStop() {
        super.onStop();
        releasePlayer();
    }

    private void releasePlayer() {
        if (player != null) {
            if (playerView != null) playerView.setPlayer(null);
            player.release();
            player = null;
        }
    }
}
