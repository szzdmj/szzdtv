package com.liskovsoft.smartyoutubetv.prefs;

import android.content.Context;

/**
 * Minimal SmartPreferences stub with defaults used by code.
 * Replace with full implementation when available.
 */
public final class SmartPreferences {
    private final Context mCtx;
    private SmartPreferences(Context ctx) { mCtx = ctx; }

    public static SmartPreferences instance(Context ctx) {
        return new SmartPreferences(ctx != null ? ctx.getApplicationContext() : null);
    }

    public boolean getFixAspectRatio() { return false; }

    public String getPlayerBufferType() { return "medium"; }

    public boolean getDecreasePlayerUITimeout() { return false; }

    // Add more preference accessors as needed by compilation errors
}
