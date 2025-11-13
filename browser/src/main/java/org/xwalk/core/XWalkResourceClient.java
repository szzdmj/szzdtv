package org.xwalk.core;

import android.net.http.SslError;
import android.webkit.ValueCallback;
import android.webkit.WebResourceResponse;

import androidx.annotation.Nullable;

/**
 * Minimal compatibility base class for XWalkResourceClient.
 * Methods match common adapter overrides in the project.
 */
public class XWalkResourceClient {
    public XWalkResourceClient() {
    }

    public void onReceivedSslError(XWalkView view, ValueCallback<Boolean> callback, SslError error) {
        if (callback != null) callback.onReceiveValue(false);
    }

    public void onReceivedLoadError(XWalkView view, int errorCode, String description, String failingUrl) {
    }

    public void onLoadStarted(XWalkView view, String url) {
    }

    public void onLoadFinished(XWalkView view, String url) {
    }

    public void onProgressChanged(XWalkView view, int progressInPercent) {
    }

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
