package com.liskovsoft.sharedutils.locale;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.annotation.NonNull;

/**
 * Minimal wrapper stub. Real implementation should adjust resources/configuration.
 */
public class LocaleContextWrapper extends ContextWrapper {
    public LocaleContextWrapper(Context base) {
        super(base);
    }

    @NonNull
    public static Context wrap(Context context, String locale) {
        // This is a no-op wrapper for compilation. Replace with real logic when available.
        return context;
    }
}
