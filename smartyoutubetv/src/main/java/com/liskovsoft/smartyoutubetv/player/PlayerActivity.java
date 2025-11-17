package com.liskovsoft.smartyoutubetv.player;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.io.File;

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

        try {
            setContentView(com.liskovsoft.smartyoutubetv.R.layout.activity_player);

            playerView = findViewById(com.liskovsoft.smartyoutubetv.R.id.player_view);
            fileNameTv = findViewById(com.liskovsoft.smartyoutubetv.R.id.tv_filename);
            volUpBtn = findViewById(com.liskovsoft.smartyoutubetv.R.id.btn_vol_up);
            volDownBtn = findViewById(com.liskovsoft.smartyoutubetv.R.id.btn_vol_down);
            brightUpBtn = findViewById(com.liskovsoft.smartyoutubetv.R.id.btn_bright_up);
            brightDownBtn = findViewById(com.liskovsoft.smartyoutubetv.R.id.btn_bright_down);

            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

            if (volUpBtn != null) volUpBtn.setOnClickListener(v -> adjustVolume(true));
            if (volDownBtn != null) volDownBtn.setOnClickListener(v -> adjustVolume(false));
            if (brightUpBtn != null) brightUpBtn.setOnClickListener(v -> adjustBrightness(true));
            if (brightDownBtn != null) brightDownBtn.setOnClickListener(v -> adjustBrightness(false));

            // Handle Intent (ACTION_VIEW)
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
            Log.e(TAG, "onCreate failed", t);
            // 写到文件，方便没有 adb 的情况下抓取堆栈
            String path = writeCrashLog(t);
            try {
                Toast.makeText(this, "启动失败: " + t.getClass().getSimpleName() + (path != null ? "\nlog: " + path : ""), Toast.LENGTH_LONG).show();
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
