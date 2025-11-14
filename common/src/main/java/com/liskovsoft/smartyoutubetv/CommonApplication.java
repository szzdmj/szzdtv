package com.liskovsoft.smartyoutubetv;

import android.app.Application;
import android.content.Context;
import com.liskovsoft.smartyoutubetv.prefs.SmartPreferences;

/**
 * Minimal CommonApplication stub to satisfy cross-module references.
 * Added getPreferences() to return a shared SmartPreferences instance.
 */
public class CommonApplication extends Application {
    private static CommonApplication sInstance;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
    }

    public static Context getContext() {
        return sInstance;
    }

    public static CommonApplication getInstance() {
        return sInstance;
    }

    /**
     * Convenience accessor used across the codebase.
     * Returns the shared SmartPreferences instance for the app context.
     */
    public static SmartPreferences getPreferences() {
        Context ctx = getContext();
        return ctx == null ? null : SmartPreferences.instance(ctx);
    }
}
