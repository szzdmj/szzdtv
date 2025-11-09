package com.example.tvapp.player

import android.content.Context
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.DefaultLoadControl

object PlayerHolder {
    @Volatile private var player: ExoPlayer? = null

    fun instance(context: Context): ExoPlayer {
        return player ?: synchronized(this) {
            player ?: buildPlayer(context).also { player = it }
        }
    }

    private fun buildPlayer(context: Context): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                5_000,   // min buffer
                15_000,  // max buffer (低内存 TV 保守)
                1_000,   // play start
                2_000    // resume
            )
            .build()

        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
    }

    fun prepareUrl(url: String) {
        val p = player ?: return
        val item = MediaItem.fromUri(url)
        p.setMediaItem(item)
        p.prepare()
    }

    fun release() {
        player?.release()
        player = null
    }
}
