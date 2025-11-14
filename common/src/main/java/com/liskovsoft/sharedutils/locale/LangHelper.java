package com.liskovsoft.sharedutils.locale;

import android.content.Context;
import java.util.Locale;

/**
 * Minimal compatibility stub for LangHelper.
 * Replace with canonical implementation from sharedutils when available.
 */
public final class LangHelper {
    private LangHelper() {}

    public static void forceLocale(Context ctx, String locale) {
        // best-effort no-op for CI / non-UI contexts
    }

    public static String guessLocale(Context ctx) {
        if (ctx == null) return Locale.getDefault().toString();
        return Locale.getDefault().toString();
    }

    public static String getDefaultLocale() {
        return Locale.getDefault().toString();
    }
}
