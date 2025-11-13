package org.xwalk.core;

import android.net.Uri;
import android.webkit.WebResourceRequest;

import java.util.Map;

/**
 * Lightweight wrapper representing a web resource request in XWalk-style.
 */
public class XWalkWebResourceRequest {
    private final WebResourceRequest mRequest;

    public XWalkWebResourceRequest(WebResourceRequest request) {
        mRequest = request;
    }

    public Uri getUrl() {
        return mRequest != null ? mRequest.getUrl() : null;
    }

    public boolean isForMainFrame() {
        return mRequest != null && mRequest.isForMainFrame();
    }

    public String getMethod() {
        return mRequest != null ? mRequest.getMethod() : "GET";
    }

    public Map<String, String> getRequestHeaders() {
        return mRequest != null ? mRequest.getRequestHeaders() : null;
    }

    public WebResourceRequest getWebResourceRequest() {
        return mRequest;
    }
}
