package com.liskovsoft.sharedutils.helpers;

import android.content.Context;
import android.widget.Toast;

/**
 * Minimal compatibility shim for MessageHelpers used in multiple modules.
 * Replace with the real implementation when available.
 */
public final class MessageHelpers {
    private MessageHelpers() {}

    public static void showLongMessage(Context ctx, String message) {
        if (ctx == null || message == null) return;
        try {
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}
    }

    public static void showMessageThrottled(Context ctx, String message) {
        showLongMessage(ctx, message);
    }

    public static void showMessage(Context ctx, String message) {
        if (ctx == null || message == null) return;
        try {
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }
}
