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
import com.szzdmj.nanohttpd.CrashLogger;
import fi.iki.elonen.NanoHTTPD;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.util.Locale;

/**
 * LauncherActivity
 *
 * - Starts a small local HTTP server that serves assets (NanoHTTPD)
 * - Logs important events to CrashLogger (internal + external file)
 * - Loads http://127.0.0.1:PORT/ when server available; falls back to file:///android_asset/gjw.html
 * - Keeps existing WebView interception for .js assets from APK assets
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

        // Init crash logger (writes to internal and external files)
        try {
            CrashLogger.init(this);
            CrashLogger.i("LauncherActivity.onCreate");
        } catch (Throwable t) {
            Log.w(TAG, "CrashLogger.init failed", t);
        }

        webView = new WebView(this);
        setContentView(webView);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            ws.setAllowFileAccessFromFileURLs(true);
            ws.setAllowUniversalAccessFromFileURLs(true);
        }

        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.setWebContentsDebuggingEnabled(true);

        // JS -> Android log bridge
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void log(String msg) {
                Log.d(TAG, "JS: " + msg);
                try { CrashLogger.i("JS: " + msg); } catch (Throwable ignored) {}
            }
        }, "Android");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                String msg = String.format(Locale.US, "JSConsole: %s (%s:%d) level=%s",
                        consoleMessage.message(),
                        consoleMessage.sourceId(),
                        consoleMessage.lineNumber(),
                        consoleMessage.messageLevel().name());
                Log.d(TAG, msg);
                try { CrashLogger.i(msg); } catch (Throwable ignored) {}
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
                    if (url.matches("(?i).+\\.(mp4|m3u8|webm)$") || url.contains("youtube.com") || url.contains("youtu.be")) {
                        Intent i = PlayerActivity.createIntent(LauncherActivity.this, Uri.parse(url));
                        startActivity(i);
                        return true;
                    }
                    return false;
                } catch (Throwable t) {
                    Log.e(TAG, "handleUrl failed", t);
                    try { CrashLogger.err("handleUrl failed", t); } catch (Throwable ignored) {}
                    return false;
                }
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
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
                    if (lower.endsWith(".js")) {
                        int idx = url.lastIndexOf('/');
                        String filename = idx >= 0 ? url.substring(idx + 1) : url;
                        String assetPath = filename;
                        Log.d(TAG, "Intercept request for JS: " + url + " -> try asset: " + assetPath);
                        try { CrashLogger.i("Intercept request for JS: " + url + " -> " + assetPath); } catch (Throwable ignored) {}
                        InputStream is = null;
                        try {
                            is = getAssets().open(assetPath);
                        } catch (IOException ignored) {
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
                    try { CrashLogger.w("tryServeAssetForUrl failed for " + url, t); } catch (Throwable ignored) {}
                }
                return null;
            }
        });

        // Start local HTTP server to serve assets
        try {
            serverPort = findFreePort();
            server = new LocalAssetsServer(serverPort, getAssets());
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            String started = "LocalAssetsServer started at http://127.0.0.1:" + serverPort + " serving assets/";
            Log.i(TAG, started);
            try { CrashLogger.i(started); } catch (Throwable ignored) {}
            // load root (/) when server available
            webView.loadUrl("http://127.0.0.1:" + serverPort + "/");
        } catch (IOException e) {
            String warn = "Failed to start LocalAssetsServer (fallback to file://): " + e.getMessage();
            Log.w(TAG, warn, e);
            try { CrashLogger.w(warn, e); } catch (Throwable ignored) {}
            server = null;
            serverPort = -1;
            webView.loadUrl("file:///android_asset/gjw.html");
        } catch (Throwable t) {
            String warn = "Unexpected error starting LocalAssetsServer, fallback to file://";
            Log.w(TAG, warn, t);
            try { CrashLogger.err(warn, t); } catch (Throwable ignored) {}
            server = null;
            serverPort = -1;
            webView.loadUrl("file:///android_asset/gjw.html");
        }
    }

    @Override
    protected void onDestroy() {
        try { CrashLogger.i("LauncherActivity.onDestroy"); } catch (Throwable ignored) {}
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        if (server != null) {
            try {
                server.stop();
                String stopped = "LocalAssetsServer stopped.";
                Log.i(TAG, stopped);
                try { CrashLogger.i(stopped); } catch (Throwable ignored) {}
            } catch (Throwable t) {
                Log.e(TAG, "Error stopping server", t);
                try { CrashLogger.err("Error stopping server", t); } catch (Throwable ignored) {}
            }
            server = null;
        }
        super.onDestroy();
    }

    private int findFreePort() throws IOException {
        // bind explicitly on loopback to avoid EACCES on some devices/ROMs
        java.net.InetAddress loopback = java.net.InetAddress.getByName("127.0.0.1");
        try (ServerSocket socket = new ServerSocket(0, 0, loopback)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    // Small LocalAssetsServer kept as inner class for convenience; you may split to its own file.
    public static class LocalAssetsServer extends NanoHTTPD {
        private static final String TAG2 = "LocalAssetsServer";
        private final AssetManager assets;

        public LocalAssetsServer(int port, AssetManager assets) throws IOException {
            super("127.0.0.1", port);
            this.assets = assets;
            Log.d(TAG2, "Constructed LocalAssetsServer for port " + port);
            try { CrashLogger.i("Constructed LocalAssetsServer for port " + port); } catch (Throwable ignored) {}
        }

        @Override
        public Response serve(IHTTPSession session) {
            String uri = session.getUri();
            String remote = session.getHeaders() != null ? session.getHeaders().get("remote-addr") : null;
            Log.d(TAG2, "Incoming request: uri=" + uri + ", remote=" + remote + ", method=" + session.getMethod());
            try { CrashLogger.i("HTTP request: " + session.getMethod() + " " + uri + " remote=" + remote); } catch (Throwable ignored) {}
            if (uri == null || uri.length() == 0 || uri.equals("/")) uri = "/gjw.html";
            String path = uri.startsWith("/") ? uri.substring(1) : uri;
            if (path.contains("..")) {
                try { CrashLogger.w("Forbidden path traversal: " + path, null); } catch (Throwable ignored) {}
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden");
            }

            try {
                // special shim path
                if ("/__shim__/id-shim.js".equals("/" + path)) {
                    InputStream in = assets.open("id-shim.js");
                    CrashLogger.i("Serving id-shim.js");
                    return newChunkedResponse(Response.Status.OK, "application/javascript", in);
                }

                InputStream is = assets.open(path);
                if (path.endsWith("index.html")) {
                    String html = readAll(is, "UTF-8");
                    html = html.replace(
                            "<script type=\"text/javascript\" src=\"webjs.js\"></script>",
                            "<script type=\"text/javascript\" src=\"/__shim__/id-shim.js\"></script>\n" +
                                    "<script type=\"text/javascript\" src=\"webjs.js\"></script>"
                    );
                    CrashLogger.i("Serving modified index.html (shim injected)");
                    Response r = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html);
                    r.addHeader("Access-Control-Allow-Origin", "*");
                    r.addHeader("Cache-Control", "no-cache");
                    return r;
                }

                String mime = guessMime(path);
                CrashLogger.i("Serving asset: " + path + " as " + mime);
                Response res = newChunkedResponse(Response.Status.OK, mime, is);
                res.addHeader("Access-Control-Allow-Origin", "*");
                res.addHeader("Cache-Control", "no-cache");
                return res;
            } catch (IOException e) {
                Log.w(TAG2, "Asset not found: " + path);
                try { CrashLogger.w("Asset not found: " + path, e); } catch (Throwable ignored) {}
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found: " + path);
            } catch (Throwable t) {
                Log.e(TAG2, "Serve exception for " + path, t);
                try { CrashLogger.err("Serve exception for " + path, t); } catch (Throwable ignored) {}
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Internal");
            }
        }

        private static String guessMime(String path) {
            String lower = path.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
            if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
            if (lower.endsWith(".css")) return "text/css; charset=utf-8";
            if (lower.endsWith(".json")) return "application/json; charset=utf-8";
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".mp4")) return "video/mp4";
            if (lower.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
            if (lower.endsWith(".ts")) return "video/mp2t";
            return "application/octet-stream";
        }

        private static String readAll(InputStream in, String enc) throws IOException {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString(enc);
        }
    }
}
