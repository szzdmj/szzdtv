package com.liskovsoft.sharedutils.locale;

import android.content.Context;
import java.util.Locale;

/**
 * Minimal LangHelper compatibility stub.
 * Replace with canonical implementation from sharedutils if/when available.
 */
public final class LangHelper {
    private LangHelper() {}

    public static void forceLocale(Context ctx, String locale) {
        // Best-effort no-op stub to avoid compilation errors.
    }

    public static String guessLocale(Context ctx) {
        return Locale.getDefault().toString();
    }

    public static String getDefaultLocale() {
        return Locale.getDefault().toString();
    }
}
