package com.google.android.exoplayer2.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

/**
 * Minimal PlayerView shim for exoplayer2 ui references.
 * This is a no-op placeholder so code that imports com.google.android.exoplayer2.ui.PlayerView compiles.
 */
public class PlayerView extends View {
    public PlayerView(Context ctx) { super(ctx); }
    public PlayerView(Context ctx, AttributeSet attrs) { super(ctx, attrs); }
    public void setPlayer(Object player) { /* no-op */ }
}
