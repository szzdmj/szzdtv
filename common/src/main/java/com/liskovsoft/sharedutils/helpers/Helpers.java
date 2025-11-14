package com.liskovsoft.sharedutils.helpers;

import android.content.Context;
import android.net.Uri;
import android.view.KeyEvent;

/**
 * Minimal Helpers shim to satisfy references during CI.
 * Added newEvent(...) utility used by GlobalKeyHandler.
 */
public final class Helpers {
    private Helpers() {}

    public static boolean isNullOrEmpty(String s) {
        return s == null || s.isEmpty();
    }

    public static String toString(Throwable t) {
        return t == null ? "" : t.toString();
    }

    public static int inferContentType(Uri uri) {
        // Placeholder: real implementation should detect content type (DASH/HLS/SS/OTHER)
        return 0;
    }

    public static String guessLocale(Context ctx) {
        return java.util.Locale.getDefault().toString();
    }

    /**
     * Create/modify a KeyEvent to a given keyCode.
     * If base is null, returns a simple ACTION_DOWN event for the code.
     * Callers use this for synthesizing navigation events.
     */
    public static KeyEvent newEvent(KeyEvent base, int keyCode) {
        if (base == null) {
            return new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
        }
        // keep timing info but replace keyCode
        return new KeyEvent(base.getDownTime(), base.getEventTime(), base.getAction(), keyCode, base.getRepeatCount(), base.getMetaState(),
                base.getDeviceId(), base.getScanCode(), base.getFlags(), base.getSource());
    }
}
