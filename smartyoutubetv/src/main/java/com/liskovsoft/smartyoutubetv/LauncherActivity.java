package com.liskovsoft.smartyoutubetv;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.liskovsoft.smartyoutubetv.player.PlayerActivity;
import fi.iki.elonen.NanoHTTPD;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.util.Locale;

/**
 * LauncherActivity with improved WebView debugging and asset-based fallback for JS files.
 *
 * - Enables file access & universal access from file URLs
 * - Logs JS console messages to Android log
 * - Intercepts requests for common JS files and serves them from assets if present
 * - For playable links, forwards to PlayerActivity
 *
 * Additionally this version starts a tiny local HTTP server (NanoHTTPD) that serves files from
 * assets/ so we can load pages via http://127.0.0.1:<port>/gjw.html which matches expectations
 * for ServiceWorker, module scripts and correct Content-Type headers.
 */
public class LauncherActivity extends AppCompatActivity {
    private static final String TAG = "LauncherActivity";
    private WebView webView;
    private LocalAssetsServer server;
    private int serverPort = -1;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);

        // Allow file:// access to other file:// and http(s) resources (for debugging / local assets)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            ws.setAllowFileAccessFromFileURLs(true);
            ws.setAllowUniversalAccessFromFileURLs(true);
        }

        // Optional tuning
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.setWebContentsDebuggingEnabled(true);

        // JS -> Android log bridge
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void log(String msg) {
                Log.d(TAG, "JS: " + msg);
            }
        }, "Android");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                // Forward JS console messages to Android logcat
                String msg = String.format(Locale.US, "JSConsole: %s (%s:%d) level=%s",
                        consoleMessage.message(),
                        consoleMessage.sourceId(),
                        consoleMessage.lineNumber(),
                        consoleMessage.messageLevel().name());
                Log.d(TAG, msg);
                return super.onConsoleMessage(consoleMessage);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl().toString());
            }

            private boolean handleUrl(String url) {
                try {
                    if (url == null) return false;
                    // if it's a video link, hand off to player
                    if (url.matches("(?i).+\\.(mp4|m3u8|webm)$") || url.contains("youtube.com") || url.contains("youtu.be")) {
                        Intent i = PlayerActivity.createIntent(LauncherActivity.this, Uri.parse(url));
                        startActivity(i);
                        return true;
                    }
                    return false;
                } catch (Throwable t) {
                    Log.e(TAG, "handleUrl failed", t);
                    return false;
                }
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // Try to respond from assets when a .js is requested and asset exists locally.
                String url = request.getUrl().toString();
                return tryServeAssetForUrl(url);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return tryServeAssetForUrl(url);
            }

            private WebResourceResponse tryServeAssetForUrl(String url) {
                try {
                    if (url == null) return null;
                    String lower = url.toLowerCase(Locale.ROOT);

                    // If requesting a JS filename (common: webjs.js), try to return it from assets if present.
                    if (lower.endsWith(".js")) {
                        // Extract filename
                        int idx = url.lastIndexOf('/');
                        String filename = idx >= 0 ? url.substring(idx + 1) : url;
                        // Normalize: in assets it's expected at root or same folder
                        String assetPath = filename;
                        Log.d(TAG, "Intercept request for JS: " + url + " -> try asset: " + assetPath);
                        InputStream is = null;
                        try {
                            is = getAssets().open(assetPath);
                        } catch (IOException ignored) {
                            // try common subpaths
                            try {
                                is = getAssets().open("js/" + assetPath);
                            } catch (IOException ignored2) {
                                is = null;
                            }
                        }
                        if (is != null) {
                            return new WebResourceResponse("application/javascript", "UTF-8", is);
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "tryServeAssetForUrl failed for " + url, t);
                }
                return null;
            }
        });

        // Start a small local HTTP server that serves assets and then load gjw.html via http://127.0.0.1:PORT/gjw.html
        // If the server cannot be started, fall back to file:///android_asset/gjw.html
        try {
            serverPort = findFreePort();
            server = new LocalAssetsServer(serverPort, getAssets());
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            Log.i(TAG, "LocalAssetsServer started at http://127.0.0.1:" + serverPort + " serving assets/");
            webView.loadUrl("http://127.0.0.1:" + serverPort + "/gjw.html");
        } catch (IOException e) {
            Log.w(TAG, "Failed to start LocalAssetsServer (will fallback to file:///): " + e.getMessage(), e);
            server = null;
            serverPort = -1;
            webView.loadUrl("file:///android_asset/gjw.html");
        } catch (Throwable t) {
            Log.w(TAG, "Unexpected error starting LocalAssetsServer, fallback to file://", t);
            server = null;
            serverPort = -1;
            webView.loadUrl("file:///android_asset/gjw.html");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        if (server != null) {
            server.stop();
            Log.i(TAG, "LocalAssetsServer stopped.");
            server = null;
        }
    }

    /**
     * Find a free ephemeral port by creating a ServerSocket on port 0 then closing it and returning the port.
     */
    private int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    /**
     * Very small NanoHTTPD based server that serves files from assets/ .
     * It returns appropriate Content-Type headers for common types and a simple 404 when missing.
     *
     * Note: This implementation intentionally keeps complexity low. If you need Range support (for
     * media seeking) or advanced caching/headers, extend this class accordingly.
     */
    private static class LocalAssetsServer extends NanoHTTPD {
        private final AssetManager assets;

        public LocalAssetsServer(int port, AssetManager assets) {
            super(port);
            this.assets = assets;
        }

        @Override
        public Response serve(IHTTPSession session) {
            String uri = session.getUri();
            if (uri == null || uri.length() == 0 || uri.equals("/")) {
                uri = "/gjw.html";
            }
            String path = uri.startsWith("/") ? uri.substring(1) : uri;
            // prevent directory traversal
            if (path.contains("..")) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden");
            }

            try {
                InputStream is = assets.open(path);
                String ext = MimeTypeMap.getFileExtensionFromUrl(path);
                String mime = null;
                if (ext != null && ext.length() > 0) {
                    mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.ROOT));
                }
                if (mime == null) {
                    // fallback common mappings
                    if (path.endsWith(".js")) mime = "application/javascript";
                    else if (path.endsWith(".html")) mime = "text/html; charset=utf-8";
                    else if (path.endsWith(".css")) mime = "text/css";
                    else if (path.endsWith(".json")) mime = "application/json";
                    else if (path.endsWith(".wasm")) mime = "application/wasm";
                    else if (path.endsWith(".mp4")) mime = "video/mp4";
                    else mime = "application/octet-stream";
                }

                Response r = newChunkedResponse(Response.Status.OK, mime, is);
                // Allow CORS for debugging or remote devtools to fetch resources
                r.addHeader("Access-Control-Allow-Origin", "*");
                r.addHeader("Cache-Control", "no-cache");
                return r;
            } catch (IOException e) {
                // not found in assets
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found: " + path);
            }
        }
    }
}
