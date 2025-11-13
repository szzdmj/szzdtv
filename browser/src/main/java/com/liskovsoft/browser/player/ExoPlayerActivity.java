package com.liskovsoft.browser.player;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

/**
 * Minimal Activity to play a media URL using AndroidX Media3 ExoPlayer (1.4.0).
 * Host can start this with ExoPlayerActivity.start(context, url).
 *
 * Simple, self-contained player for incremental integration:
 * - Creates ExoPlayer and PlayerView programmatically (no layout file)
 * - Plays provided URL (mp4 / m3u8 / webm / etc)
 * - Releases player on destroy
 */
public class ExoPlayerActivity extends AppCompatActivity {
    private static final String EXTRA_URL = "extra_url";
    private ExoPlayer player;
    private PlayerView playerView;

    public static void start(Context ctx, String url) {
        Intent i = new Intent(ctx, ExoPlayerActivity.class);
        i.putExtra(EXTRA_URL, url);
        ctx.startActivity(i);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Programmatic PlayerView to avoid layout file changes for now
        playerView = new PlayerView(this);
        playerView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(playerView);

        String url = getIntent() != null ? getIntent().getStringExtra(EXTRA_URL) : null;
        if (url == null) {
            finish();
            return;
        }

        // Create and prepare player (Media3 ExoPlayer)
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        MediaItem item = MediaItem.fromUri(Uri.parse(url));
        player.setMediaItem(item);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) {
            player.setPlayWhenReady(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null) {
            player.setPlayWhenReady(true);
        }
    }

    @Override
    protected void onDestroy() {
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
