package com.liskovsoft.sharedutils.helpers;

import android.content.Context;
import android.net.Uri;

/**
 * Minimal Helpers stub to satisfy references during CI.
 * Expand as real implementation becomes available.
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
        // placeholder, real logic lives elsewhere
        return 0;
    }

    public static String guessLocale(Context ctx) {
        return java.util.Locale.getDefault().toString();
    }
}
