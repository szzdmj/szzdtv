package com.liskovsoft.sharedutils.helpers;

import android.content.Context;
import android.net.Uri;

/**
 * Minimal Helpers shim to satisfy references during CI.
 * Expand with real implementations later.
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
}
