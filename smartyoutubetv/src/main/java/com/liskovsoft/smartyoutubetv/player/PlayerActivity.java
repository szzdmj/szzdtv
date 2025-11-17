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
    protected void onCreate(@Nullable Bundle savedInstanceState) {
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
            if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
                Uri data = intent.getData();
                if (data != null) {
                    if (fileNameTv != null) fileNameTv.setText(extractDisplayName(data));
                    initPlayerWithUri(data);
                } else {
                    Toast.makeText(this, "No media uri provided", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "No Intent.ACTION_VIEW - call with a media Uri", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            // Catch any unexpected runtime exceptions after fallback and log them
            Log.e(TAG, "onCreate post-inflate handling failed", t);
            writeCrashLog(t);
            Toast.makeText(this, "启动失败: " + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    /**
     * Create a minimal programmatic fallback layout that contains a PlayerView.
     * This is used only when normal XML inflation fails (to allow running on-device without crashing).
     */
    private void createFallbackLayout() {
        try {
            FrameLayout root = new FrameLayout(this);
            FrameLayout.LayoutParams rootLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
            root.setLayoutParams(rootLp);

            PlayerView pv = new PlayerView(this);
            // generate an id to allow later findViewById if needed
            pv.setId(View.generateViewId());
            FrameLayout.LayoutParams pvLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
            pv.setLayoutParams(pvLp);

            root.addView(pv);

            setContentView(root);
            playerView = pv;

            // create minimal overlays as null - callers should guard against null views
            fileNameTv = null;
            volUpBtn = null;
            volDownBtn = null;
            brightUpBtn = null;
            brightDownBtn = null;
        } catch (Throwable t) {
            // If even fallback fails, write log and finish
            Log.e(TAG, "createFallbackLayout failed", t);
            writeCrashLog(t);
            try {
                Toast.makeText(this, "布局加载失败", Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {}
            finish();
        }
    }

    private void initPlayerWithUri(Uri uri) {
        // build player on demand
        if (player == null) {
            player = new ExoPlayer.Builder(this).build();
            if (playerView != null) playerView.setPlayer(player);
        }
        MediaItem mediaItem = MediaItem.fromUri(uri);
        player.setMediaItem(mediaItem);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private String extractDisplayName(Uri uri) {
        String last = uri.getLastPathSegment();
        if (last != null && !last.isEmpty()) {
            return last;
        }
        // try file name if file scheme
        if ("file".equals(uri.getScheme())) {
            try {
                File f = new File(uri.getPath());
                return f.getName();
            } catch (Exception ignored) {}
        }
        return uri.toString();
    }

    private void adjustVolume(boolean up) {
        if (audioManager == null) return;
        int flags = AudioManager.FLAG_PLAY_SOUND | AudioManager.FLAG_SHOW_UI;
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, flags);
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
