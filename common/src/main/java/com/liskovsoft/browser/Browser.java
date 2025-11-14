package com.liskovsoft.browser;

/**
 * Minimal Browser stub used by project references.
 * Replace with the real implementation when available.
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

    public void loadUrl(String url) { /* no-op */ }

    public void stop() { /* no-op */ }
}
