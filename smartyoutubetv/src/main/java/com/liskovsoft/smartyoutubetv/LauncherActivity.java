package com.liskovsoft.smartyoutubetv;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.net.http.SslError;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.CookieManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.liskovsoft.smartyoutubetv.player.PlayerActivity;
import com.szzdmj.nanohttpd.CrashLogger;
import fi.iki.elonen.NanoHTTPD;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

public class LauncherActivity extends AppCompatActivity {
    private static final String TAG = "LauncherActivity";

    private WebView webView;
    private LocalAssetsServer server;
    private int serverPort = -1;

    // Minimal debug flags
    private static final boolean ALLOW_ALL_SSL_ERRORS = true;

    // debug request counting map
    private final java.util.Map<String, Integer> requestCounts = Collections.synchronizedMap(new java.util.HashMap<>());

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        safeInitCrashLogger();

        webView = new WebView(this);
        setContentView(webView);

        // Basic WebView settings
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

        // Cookies
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(webView, true);
        } catch (Throwable e) {
            safeLogWarn("CookieManager init failed: " + e);
        }

        // Simple JS-to-Android bridge for logs
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void log(String msg) {
                Log.d(TAG, "JS: " + msg);
                safeLogInfo("JS: " + msg);
            }
        }, "Android");

        webView.setWebChromeClient(new WebChromeClient(){
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage){
                String msg = String.format(Locale.US, "JSConsole: %s (%s:%d) level=%s",
                        consoleMessage.message(),
                        consoleMessage.sourceId(),
                        consoleMessage.lineNumber(),
                        consoleMessage.messageLevel().name());
                Log.d(TAG, msg);
                safeLogInfo(msg);
                return super.onConsoleMessage(consoleMessage);
            }
        });

        webView.setWebViewClient(new WebViewClient(){
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request){
                String url = request.getUrl().toString();
                return handleUrl(url);
            }
            @Override @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url){ return handleUrl(url); }

            private boolean handleUrl(String url){
                try {
                    if (url == null) return false;
                    if (url.matches("(?i).+\\.(mp4|m3u8|webm)$") || url.contains("youtube.com") || url.contains("youtu.be")) {
                        startActivity(PlayerActivity.createIntent(LauncherActivity.this, Uri.parse(url)));
                        return true;
                    }
                    return false;
                } catch (Throwable t) {
                    Log.e(TAG,"handleUrl failed",t);
                    safeLogWarn("handleUrl failed: " + t);
                    return false;
                }
            }

            // Intercept requests only to serve local APK assets; for external http(s) return null so WebView fetches directly
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request){
                safeLogInfo("shouldInterceptRequest: " + request.getUrl());
                return LauncherActivity.this.tryServeAssetForUrl(request.getUrl().toString(), request.getRequestHeaders());
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error){
                try{
                    String s = "onReceivedSslError: primaryError=" + error.getPrimaryError() + " url=" + (view!=null?view.getUrl():"(unknown)");
                    Log.w(TAG,s);
                    safeLogWarn(s);
                } catch (Throwable t) {
                    Log.w(TAG,"Exception in onReceivedSslError",t);
                    safeLogWarn("Exception in onReceivedSslError: " + t);
                }
                // For debugging: optionally proceed (INSECURE)
                if (ALLOW_ALL_SSL_ERRORS) {
                    safeLogInfo("Proceeding on SSL error because ALLOW_ALL_SSL_ERRORS=true");
                    handler.proceed();
                } else {
                    handler.cancel();
                }
            }
        });

        // Start local assets server (serves embedded assets only)
        try {
            serverPort = findFreePort();
            server = new LocalAssetsServer(serverPort, getAssets());
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            String started = "LocalAssetsServer started at http://127.0.0.1:" + serverPort;
            Log.i(TAG, started);
            safeLogInfo(started);
            // Load local server root
            webView.loadUrl("http://127.0.0.1:" + serverPort + "/");
        } catch (IOException e) {
            Log.w(TAG, "Failed to start LocalAssetsServer, fallback to asset file", e);
            safeLogWarn("Failed to start LocalAssetsServer: " + e);
            webView.loadUrl("file:///android_asset/gjw.html");
        }
    }

    // Try to serve local asset files only. For external URLs (http/https) return null and let WebView handle network.
    private WebResourceResponse tryServeAssetForUrl(String url, Map<String, String> requestHeaders){
        try {
            if (url == null) return null;
            String lower = url.toLowerCase(Locale.ROOT);

            // Count requests for this URL (simple rate-limiting of logs)
            int count = 1;
            try {
                Integer prev = requestCounts.get(lower);
                count = (prev == null) ? 1 : prev + 1;
                requestCounts.put(lower, count);
            } catch (Throwable t) {
                // ignore counting error
            }

            // Decision log (limited by count)
            String info = String.format(Locale.US, "Intercept decision: url=%s count=%d", url, count);
            Log.i(TAG, info);
            safeLogInfo(info);

            // 1) Bypass local server requests (let NanoHTTPD/LocalAssetsServer handle these)
            if (lower.startsWith("http://127.0.0.1:") || lower.startsWith("http://localhost:")) {
                String msg = "Bypass local request -> letting LocalAssetsServer handle: " + url;
                Log.d(TAG, msg);
                safeLogInfo(msg);
                return null;
            }

            // 2) If this is a request for a packaged JS file, try to serve from assets and log that action
            if (lower.endsWith(".js")) {
                String name = url.substring(url.lastIndexOf('/') + 1).split("\\?")[0];
                try {
                    InputStream is = getAssets().open(name);
                    String msg = "Serving JS from assets: " + name + " for url=" + url;
                    Log.i(TAG, msg);
                    safeLogInfo(msg);
                    return new WebResourceResponse("application/javascript", "UTF-8", is);
                } catch (IOException ioe1) {
                    try {
                        InputStream is = getAssets().open("js/" + name);
                        String msg = "Serving JS from assets/js/: " + name + " for url=" + url;
                        Log.i(TAG, msg);
                        safeLogInfo(msg);
                        return new WebResourceResponse("application/javascript", "UTF-8", is);
                    } catch (IOException ioe2) {
                        // asset not present in APK: record and allow WebView to load from network
                        String msg = "Asset not found in APK for " + name + "; allowing WebView to fetch: " + url;
                        Log.i(TAG, msg);
                        safeLogInfo(msg);
                        // fall through to network branch -> return null
                    }
                }
            }

            // 3) For all external http(s) requests: do not proxy; record brief header summary and return null
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                String msg = "Allowing WebView to fetch network resource directly: " + url;
                Log.i(TAG, msg);
                safeLogInfo(msg);

                // Request header summary logging (avoid printing huge headers)
                try {
                    if (requestHeaders != null && !requestHeaders.isEmpty()) {
                        String hdrSummary = "headers_count=" + requestHeaders.size();
                        safeLogInfo("Request headers summary for " + url + " -> " + hdrSummary);
                        // If you want full headers, uncomment next line (may be verbose):
                        // safeLogInfo("RequestHeaders: " + requestHeaders.toString());
                    }
                } catch (Throwable t) {
                    // ignore header logging error
                }

                // Suppress repeated logs for very high-frequency URLs (simple threshold)
                try {
                    if (count > 20) {
                        if (count == 21) {
                            safeLogInfo("High-frequency request: suppressing further per-request logs for " + url);
                        }
                        return null;
                    }
                } catch (Throwable t) {
                    // ignore
                }

                return null;
            }

        } catch (Throwable t) {
            Log.w(TAG, "tryServeAssetForUrl failed for " + url, t);
            safeLogWarn("tryServeAssetForUrl failed for " + url + " -> " + t);
        }
        return null;
    }

    // ---------- LocalAssetsServer: minimal static asset server (no proxying) ----------
    public static class LocalAssetsServer extends NanoHTTPD {
        private final AssetManager assets;
        private final int listeningPort;

        public LocalAssetsServer(int port, AssetManager assets) throws IOException {
            super("127.0.0.1", port);
            this.assets = assets;
            this.listeningPort = port;
        }

        @Override
        public Response serve(IHTTPSession session) {
            String uri = session.getUri();
            if (uri == null || uri.equals("/")) uri = "/gjw.html";
            String path = uri.startsWith("/") ? uri.substring(1) : uri;
            if (path.contains("..")) return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden");

            try {
                // Special-case: inject CSP that upgrades insecure requests in gjw.html
                if ("gjw.html".equals(path)) {
                    InputStream is = assets.open(path);
                    String html = readAll(is, "UTF-8");
                    // Add CSP upgrade-insecure-requests to force http -> https for subresources
                    String injection = "<meta http-equiv=\"Content-Security-Policy\" content=\"upgrade-insecure-requests; default-src * 'unsafe-inline' 'unsafe-eval' data: blob:; frame-ancestors *;\">";
                    String base = "<base href=\"http://localhost:" + listeningPort + "/\">";
                    if (html.toLowerCase(Locale.ROOT).contains("<head")) {
                        html = html.replaceFirst("(?i)<head([^>]*)>", "<head$1>" + injection + base);
                    } else {
                        html = injection + base + html;
                    }
                    Response r2 = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html);
                    r2.addHeader("Access-Control-Allow-Origin", "*");
                    r2.addHeader("Cache-Control", "no-cache");
                    return r2;
                }

                InputStream is = assets.open(path);
                String mime = guessMimeStatic(path);
                Response res = newChunkedResponse(Response.Status.OK, mime, is);
                res.addHeader("Access-Control-Allow-Origin", "*");
                return res;
            } catch (IOException e) {
                // Not found in assets: fallback to 404 simple response.
                final String ONE_PX_PNG_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR4nGNgYAAAAAMAAWgmWQ0AAAAASUVORK5CYII=";
                if (path.equalsIgnoreCase("favicon.ico") || path.equalsIgnoreCase("favicon.png")) {
                    byte[] bytes = Base64.decode(ONE_PX_PNG_BASE64, Base64.DEFAULT);
                    InputStream is = new ByteArrayInputStream(bytes);
                    return newChunkedResponse(Response.Status.OK, "image/png", is);
                }
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found: " + path);
            }
        }

        // helper to read InputStream into String
        private static String readAll(InputStream in, String enc) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString(enc);
        }

        static String guessMimeStatic(String path) {
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
    }

    // helper: delete files/directories recursively (unchanged)
    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        f.delete();
    }

    private int findFreePort() throws IOException {
        java.net.InetAddress loopback = java.net.InetAddress.getByName("127.0.0.1");
        try (ServerSocket socket = new ServerSocket(0, 0, loopback)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    // ----------------- Safe logging helpers -----------------
    private void safeInitCrashLogger() {
        try { CrashLogger.init(this); CrashLogger.i("LauncherActivity.onCreate"); } catch (Throwable t) { Log.w(TAG, "CrashLogger init failed", t); }
    }

    private void safeLogInfo(String msg) {
        try { CrashLogger.i(msg); } catch (Throwable t) { /* ignore */ }
    }

    private void safeLogWarn(String msg) {
        try { CrashLogger.w(msg, null); } catch (Throwable t) { /* ignore */ }
    }
}
