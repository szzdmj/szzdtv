package com.liskovsoft.browser;

/**
 * Minimal Browser stub used by project references.
 * Expand this with real behavior as needed.
 */
public final class Browser {
    public enum EngineType {
        WEBVIEW,
        XWALK,
        EXOPLAYER
    }

    private final EngineType mEngine;

    public Browser(EngineType engine) {
        mEngine = engine;
    }

    public EngineType getEngineType() {
        return mEngine;
    }

    // minimal placeholder methods used by callers
    public void loadUrl(String url) { /* no-op for CI */ }
    public void stop() { /* no-op */ }
}
