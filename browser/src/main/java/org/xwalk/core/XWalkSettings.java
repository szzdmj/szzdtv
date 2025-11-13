package org.xwalk.core;

import android.webkit.WebSettings;

/**
 * Minimal wrapper to present an XWalkSettings-like API backed by WebSettings.
 * Implements only the methods needed by the project.
 */
public class XWalkSettings {
    public enum LayoutAlgorithm {
        NORMAL,
        SINGLE_COLUMN,
        NARROW_COLUMNS,
        TEXT_AUTOSIZING
    }

    private final WebSettings mWebSettings;
    private LayoutAlgorithm mLayoutAlgorithm = LayoutAlgorithm.NORMAL;

    public XWalkSettings(WebSettings webSettings) {
        mWebSettings = webSettings;
    }

    public void setJavaScriptEnabled(boolean flag) {
        mWebSettings.setJavaScriptEnabled(flag);
    }

    public void setDomStorageEnabled(boolean flag) {
        mWebSettings.setDomStorageEnabled(flag);
    }

    public void setMediaPlaybackRequiresUserGesture(boolean require) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
            mWebSettings.setMediaPlaybackRequiresUserGesture(require);
        }
    }

    public void setAllowFileAccess(boolean allow) {
        mWebSettings.setAllowFileAccess(allow);
    }

    public void setLoadsImagesAutomatically(boolean loads) {
        mWebSettings.setLoadsImagesAutomatically(loads);
    }

    public void setUseWideViewPort(boolean use) {
        mWebSettings.setUseWideViewPort(use);
    }

    public void setLoadWithOverviewMode(boolean overview) {
        mWebSettings.setLoadWithOverviewMode(overview);
    }

    public LayoutAlgorithm getLayoutAlgorithm() {
        return mLayoutAlgorithm;
    }

    public void setLayoutAlgorithm(LayoutAlgorithm alg) {
        mLayoutAlgorithm = alg;
        // no-op mapping
    }

    public WebSettings getWebSettings() {
        return mWebSettings;
    }
}
