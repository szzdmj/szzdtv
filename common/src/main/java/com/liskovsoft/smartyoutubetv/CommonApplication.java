package com.liskovsoft.smartyoutubetv;

import android.app.Application;
import android.content.Context;
import android.util.Log;
import com.liskovsoft.smartyoutubetv.prefs.SmartPreferences;

/**
 * Minimal CommonApplication stub made safer: critical initialization is wrapped in try/catch
 * to avoid the app crashing immediately on startup. This is a temporary safety net; please
 * still fix the root cause (see logs) and remove the catch when resolved.
 */
public class CommonApplication extends Application {
    private static final String TAG = "CommonApplication";
    private static CommonApplication sInstance;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        try {
            // Place lightweight/critical initialization here. Heavy init should be deferred.
            // Example safe initializations (none by default). Keep this block minimal.
        } catch (Throwable t) {
            // Log but do not rethrow - prevent startup crash.
            Log.e(TAG, "Application onCreate safe init failed", t);
        }
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
