package org.xwalk.core;

import android.content.Context;
import android.util.AttributeSet;
import android.webkit.WebView;
import android.webkit.WebSettings;

/**
 * Minimal compatibility wrapper so code that expects org.xwalk.core.XWalkView
 * can compile and operate on top of android.webkit.WebView.
 *
 * Delegates rendering to system WebView and exposes an XWalkSettings wrapper.
 */
public class XWalkView extends WebView {
    private final XWalkSettings mXWalkSettings;

    public XWalkView(Context context) {
        super(context);
        mXWalkSettings = new XWalkSettings(super.getSettings());
    }

    public XWalkView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mXWalkSettings = new XWalkSettings(super.getSettings());
    }

    public XWalkView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mXWalkSettings = new XWalkSettings(super.getSettings());
    }

    /**
     * Compatibility: return XWalkSettings wrapper instead of WebSettings.
     */
    public XWalkSettings getSettings() {
        return mXWalkSettings;
    }

    // Compatibility stubs used by adapters
    public void setUIClient(XWalkUIClient client) {
        // no-op shim
    }

    public void load(String url) {
        super.loadUrl(url);
    }
}
