package com.liskovsoft.smartyoutubetv;

import android.app.Application;
import android.content.Context;
import com.liskovsoft.smartyoutubetv.prefs.SmartPreferences;

/**
 * Minimal CommonApplication stub used by code references.
 * Real application class may provide more features (MultiDex, caches, event bus, etc.)
 */
public class CommonApplication extends Application {
    private static Context sApp;

    @Override
    public void onCreate() {
        super.onCreate();
        sApp = this;
    }

    public static Context getAppContext() {
        return sApp;
    }

    /**
     * Convenience method to match existing code references.
     * Uses SmartPreferences.instance(context) from prefs module.
     */
    public static SmartPreferences getPreferences() {
        if (sApp == null) return null;
        return SmartPreferences.instance(sApp);
    }
}
