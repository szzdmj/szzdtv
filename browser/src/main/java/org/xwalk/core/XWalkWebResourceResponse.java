package org.xwalk.core;

import android.webkit.WebResourceResponse;

import java.io.InputStream;

/**
 * Lightweight wrapper for WebResourceResponse to serve as XWalkWebResourceResponse.
 */
public class XWalkWebResourceResponse {
    private final WebResourceResponse mResponse;

    public XWalkWebResourceResponse(String mimeType, String encoding, InputStream data) {
        this.mResponse = new WebResourceResponse(mimeType, encoding, data);
    }

    public XWalkWebResourceResponse(WebResourceResponse resp) {
        this.mResponse = resp;
    }

    public WebResourceResponse getWebResourceResponse() {
        return mResponse;
    }
}
