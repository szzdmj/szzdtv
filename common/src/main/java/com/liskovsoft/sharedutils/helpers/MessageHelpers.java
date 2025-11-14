package com.liskovsoft.sharedutils.helpers;

import android.content.Context;
import android.widget.Toast;

/**
 * Minimal compatibility stub for MessageHelpers used by common module.
 * If you have an upstream sharedutils library, replace/remove this stub and
 * add proper dependency coordinates instead.
 */
public final class MessageHelpers {
    private MessageHelpers() {}

    public static void showLongMessage(Context ctx, String message) {
        if (ctx == null || message == null) return;
        try {
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {
            // Safe no-op in non-UI contexts.
        }
    }
}
