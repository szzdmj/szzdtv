package org.xwalk.core;

import android.net.http.SslError;
import android.webkit.ValueCallback;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import androidx.annotation.Nullable;

/**
 * Minimal compatibility base class for XWalkResourceClient.
 * Methods provided are those adapters typically override in this project.
 * Implementations can call underlying WebView equivalents as needed.
 */
public class XWalkResourceClient {
    public XWalkResourceClient() {
    }

    // Called when an SSL error occurs
    public void onReceivedSslError(XWalkView view, ValueCallback<Boolean> callback, SslError error) {
        // default: ignore / cancel
        if (callback != null) callback.onReceiveValue(false);
    }

    // Error handling
    public void onReceivedLoadError(XWalkView view, int errorCode, String description, String failingUrl) {
    }

    public void onLoadStarted(XWalkView view, String url) {
    }

    public void onLoadFinished(XWalkView view, String url) {
    }

    public void onProgressChanged(XWalkView view, int progressInPercent) {
    }

    /**
     * Map to android.webkit.WebResourceResponse when possible.
     */
    @Nullable
    public XWalkWebResourceResponse shouldInterceptLoadRequest(XWalkView view, String url) {
        return null;
    }

    @Nullable
    public XWalkWebResourceResponse shouldInterceptLoadRequest(XWalkView view, XWalkWebResourceRequest request) {
        return null;
    }

    public boolean shouldOverrideUrlLoading(XWalkView view, String url) {
        return false;
    }
}
