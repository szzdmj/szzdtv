package com.liskovsoft.sharedutils.helpers;

import android.content.Context;
import android.net.Uri;

public final class Helpers {
    private Helpers() {}

    public static boolean isNullOrEmpty(String s) {
        return s == null || s.isEmpty();
    }

    public static String toString(Throwable t) {
        return t == null ? "" : t.toString();
    }

    public static int inferContentType(Uri uri) {
        // Placeholder value; real implementation should detect content type.
        return 0;
    }

    public static String guessLocale(Context ctx) {
        return java.util.Locale.getDefault().toString();
    }
}
