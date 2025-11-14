package com.google.android.exoplayer2;

/**
 * Minimal compatibility shim for com.google.android.exoplayer2.SimpleExoPlayer.
 * This stub is only to satisfy compile-time references. Replace with adapters
 * or the real exoplayer2 types if you intend to use ExoPlayer v2 APIs.
 */
public class SimpleExoPlayer {
    public void addListener(Object listener) {}
    public void removeListener(Object listener) {}
    public void setPlayWhenReady(boolean playWhenReady) {}
    public int getCurrentWindowIndex() { return 0; }
    public long getCurrentPosition() { return 0L; }
    public boolean isCurrentWindowSeekable() { return true; }
    public void seekTo(int windowIndex, long positionMs) {}
    public void prepare(Object mediaSource) {}
    public void setAudioAttributes(Object attrs, boolean handleAudioFocus) {}
    // Add methods as needed by compilation in small increments
}
