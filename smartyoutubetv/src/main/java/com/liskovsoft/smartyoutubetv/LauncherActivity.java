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
import android.webkit.WebResourceError;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * LauncherActivity
 *
 * - Starts a small local HTTP server that serves assets (NanoHTTPD)
 * - Logs important events to CrashLogger
 * - Loads http://localhost:PORT/ when server available; falls back to file:///android_asset/gjw.html
 * - Intercepts http:// requests and forces HTTPS fetch (returns HTTPS content to WebView).
 * - If HTTPS fetch fails, returns a safe empty/no-content response to prevent WebView from issuing cleartext HTTP.
 */
public class LauncherActivity extends AppCompatActivity {
    private static final String TAG = "LauncherActivity";
    private WebView webView;
    private LocalAssetsServer server;
    private int serverPort = -1;

    // 去抖：上次启动的 URL 与时间（避免短时间内重复打开播放器）
    private volatile String lastLaunchedUrl = null;
    private volatile long lastLaunchTs = 0;
    private static final long LAUNCH_DEBOUNCE_MS = 1500;

    // HTTPS fallback settings (KEEP unchanged per your note)
    private static final boolean INSECURE_HTTPS_FALLBACK = true; // set false for production
    private static final String[] HTTPS_WHITELIST_SUFFIXES = new String[] {
    };

    // Tracking missing assets (optional helper)
    private final Set<String> missingAssets = Collections.synchronizedSet(new HashSet<>());

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Init CrashLogger
        try { CrashLogger.init(this); CrashLogger.i("LauncherActivity.onCreate"); } catch (Throwable t) { Log.w(TAG, "CrashLogger init failed", t); }

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

        // JS -> Android bridge
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void log(String msg) {
                Log.d(TAG, "JS: " + msg);
                try { CrashLogger.i("JS: " + msg); } catch (Throwable ignored) {}
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
                try { CrashLogger.i(msg); } catch (Throwable ignored) {}
                return super.onConsoleMessage(consoleMessage);
            }
        });

        webView.setWebViewClient(new WebViewClient(){
            @Override @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url){ return handleUrl(url); }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request){ return handleUrl(request.getUrl().toString()); }

            private boolean handleUrl(String url){
                try{
                    if (url == null) return false;
                    if (url.matches("(?i).+\\.(mp4|m3u8|webm)$") || url.contains("youtube.com") || url.contains("youtu.be")){
                        Intent i = PlayerActivity.createIntent(LauncherActivity.this, Uri.parse(url));
                        startActivity(i);
                        return true;
                    }
                    return false;
                }catch(Throwable t){
                    Log.e(TAG,"handleUrl failed",t); try{ CrashLogger.err("handleUrl failed",t);}catch(Throwable ignored){}
                    return false;
                }
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request){
                String url = request.getUrl().toString();
                try { CrashLogger.i("shouldInterceptRequest: "+url); } catch (Throwable ignored) {}
                return tryServeAssetForUrl(url);
            }
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url){
                try { CrashLogger.i("shouldInterceptRequest: "+url); } catch (Throwable ignored) {}
                return tryServeAssetForUrl(url);
            }

            private WebResourceResponse tryServeAssetForUrl(String url){
                try{
                    if (url == null) return null;
                    String lower = url.toLowerCase(Locale.ROOT);

                    // strip query and fragment so filenames like a.js?v=123 don't fail asset lookup
                    String urlNoQuery = url.split("\\?")[0].split("#")[0];

                    // If requesting a JS filename (common: webjs.js), try to return it from assets if present.
                    if (lower.endsWith(".js")) {
                        int idx = urlNoQuery.lastIndexOf('/');
                        String filename = idx >= 0 ? urlNoQuery.substring(idx + 1) : urlNoQuery;
                        String assetPath = filename;
                        Log.d(TAG, "Intercept request for JS: " + url + " -> " + assetPath);
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
                            Log.i(TAG, "Serving JS from assets: " + assetPath);
                            return new WebResourceResponse("application/javascript", "UTF-8", is);
                        } else {
                            // record missing asset for later debugging
                            missingAssets.add(assetPath);
                            Log.d(TAG, "Asset not found for " + assetPath + ", will attempt HTTPS-only network fetch");
                        }
                    }

                    // If it's a local asset path (http(s) pointing to localhost), let LocalAssetsServer handle it
                    if (lower.startsWith("http://localhost:") || lower.startsWith("http://127.0.0.1:") ||
                        lower.startsWith("https://localhost:") || lower.startsWith("https://127.0.0.1:")) {
                        return null;
                    }

                    // For ANY http:// external requests — DO NOT let WebView attempt cleartext.
                    // Instead, attempt HTTPS fetch and return that content. If HTTPS fails, return a safe 204/NoContent
                    if (lower.startsWith("http://")) {
                        WebResourceResponse httpsResp = tryFetchHttpsFallback(url);
                        if (httpsResp != null) {
                            try { CrashLogger.i("HTTPS fallback succeeded for " + url); } catch (Throwable ignored) {}
                            return httpsResp;
                        } else {
                            try { CrashLogger.i("HTTPS fallback failed for " + url + " — blocking cleartext request"); } catch (Throwable ignored) {}
                            // Prevent WebView from trying plain http (which would be blocked by policy).
                            // Return a 204 No Content (API21+) or an empty response for older devices.
                            if (Build.VERSION.SDK_INT >= 21) {
                                Map<String, String> headers = Collections.singletonMap("Content-Type", "text/plain");
                                return new WebResourceResponse("text/plain", "UTF-8", 204, "No Content", headers, new ByteArrayInputStream(new byte[0]));
                            } else {
                                return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                            }
                        }
                    }

                }catch(Throwable t){
                    Log.w(TAG,"tryServeAssetForUrl failed for "+url,t);
                    try{ CrashLogger.w("tryServeAssetForUrl failed for "+url,t);}catch(Throwable ignored){}
                }
                return null;
            }

            @Override @SuppressWarnings("deprecation")
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl){
                String s = "onReceivedError (old): code="+errorCode+" desc="+description+" url="+failingUrl;
                Log.w(TAG,s); try{ CrashLogger.w(s,null);}catch(Throwable ignored){}
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error){
                try{
                    String url = request!=null?request.getUrl().toString():"(unknown)";
                    String s = "onReceivedError: url="+url+" code="+error.getErrorCode()+" desc="+error.getDescription();
                    Log.w(TAG,s); try{ CrashLogger.w(s,null);}catch(Throwable ignored){}
                }catch(Throwable t){ Log.w(TAG,"Exception in onReceivedError",t); try{ CrashLogger.w("Exception in onReceivedError",t);}catch(Throwable ignored){} }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse){
                try{
                    String url = request!=null?request.getUrl().toString():"(unknown)";
                    int status = errorResponse!=null?errorResponse.getStatusCode():-1;
                    String s = "onReceivedHttpError: url="+url+" status="+status;
                    Log.w(TAG,s); try{ CrashLogger.w(s,null);}catch(Throwable ignored){}
                }catch(Throwable t){ Log.w(TAG,"Exception in onReceivedHttpError",t); try{ CrashLogger.w("Exception in onReceivedHttpError",t);}catch(Throwable ignored){} }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error){
                try{
                    String s = "onReceivedSslError: primaryError="+error.getPrimaryError()+" url="+(view!=null?view.getUrl():"(unknown)");
                    Log.w(TAG,s); try{ CrashLogger.w(s,null);}catch(Throwable ignored){}
                }catch(Throwable t){ Log.w(TAG,"Exception in onReceivedSslError",t); try{ CrashLogger.w("Exception in onReceivedSslError",t);}catch(Throwable ignored){} }
                handler.cancel();
            }
        });

        // Start server
        try {
            serverPort = findFreePort();
            server = new LocalAssetsServer(serverPort, getAssets());
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            String started = "LocalAssetsServer started at http://127.0.0.1:" + serverPort + " serving assets/";
            Log.i(TAG, started);
            try { CrashLogger.i(started); } catch (Throwable ignored) {}
            // use hostname 'localhost' to match network_security_config
            webView.loadUrl("http://localhost:" + serverPort + "/");
        } catch (IOException e) {
            String warn = "Failed to start LocalAssetsServer (fallback to file://): " + e.getMessage();
            Log.w(TAG,warn,e); try{ CrashLogger.w(warn,e);}catch(Throwable ignored){}
            server=null; serverPort=-1;
            webView.loadUrl("file:///android_asset/gjw.html");
        } catch (Throwable t) {
            String warn = "Unexpected error starting LocalAssetsServer, fallback to file://";
            Log.w(TAG,warn,t); try{ CrashLogger.err(warn,t);}catch(Throwable ignored){}
            server=null; serverPort=-1;
            webView.loadUrl("file:///android_asset/gjw.html");
        }
    } // end onCreate

    // ---------- Class-level helper: launch internal player if media URL detected ----------
    private void tryLaunchPlayerIfMedia(final String url) {
        if (url == null) return;
        // 基础匹配（文件后缀/扩展名），你可以根据需要扩展正则
        String lower = url.toLowerCase(Locale.ROOT);
        boolean looksLikeMedia = lower.endsWith(".m3u8") || lower.endsWith(".mp4") || lower.endsWith(".webm") ||
                lower.endsWith(".m4a") || lower.endsWith(".aac");
        if (!looksLikeMedia) return;

        final long now = System.currentTimeMillis();
        if (url.equals(lastLaunchedUrl) && (now - lastLaunchTs) < LAUNCH_DEBOUNCE_MS) {
            // 已经在短时间内启动过同一 URL，忽略
            return;
        }
        lastLaunchedUrl = url;
        lastLaunchTs = now;

        // 启动 PlayerActivity 必须在 UI 线程
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    CrashLogger.i("Launching player for " + url);
                } catch (Throwable ignored) {}
                try {
                    Intent i = PlayerActivity.createIntent(LauncherActivity.this, Uri.parse(url));
                    // 约定 extra：自动全屏并立即播放
                    i.putExtra("auto_fullscreen", true);
                    i.putExtra("auto_play", true);
                    startActivity(i);
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to launch PlayerActivity", t);
                    try { CrashLogger.w("Failed to launch PlayerActivity", t); } catch (Throwable ignored) {}
                }
            }
        });
    }

    @Override
    protected void onDestroy(){
        try { CrashLogger.i("LauncherActivity.onDestroy"); } catch (Throwable ignored) {}
        if (webView!=null){ webView.destroy(); webView=null; }
        if (server!=null){
            try{ server.stop(); String stopped="LocalAssetsServer stopped."; Log.i(TAG,stopped); try{ CrashLogger.i(stopped);}catch(Throwable ignored){} }catch(Throwable t){ Log.e(TAG,"Error stopping server",t); try{ CrashLogger.w("Error stopping server",t);}catch(Throwable ignored){} }
            server=null;
        }
        super.onDestroy();
    }

    private int findFreePort() throws IOException {
        java.net.InetAddress loopback = java.net.InetAddress.getByName("127.0.0.1");
        try (ServerSocket socket = new ServerSocket(0,0,loopback)) { socket.setReuseAddress(true); return socket.getLocalPort(); }
    }

    // LocalAssetsServer (内置) — returns assets and proxy remote URLs
    public static class LocalAssetsServer extends NanoHTTPD {
        private static final String TAG2 = "LocalAssetsServer";
        private final AssetManager assets;

        public LocalAssetsServer(int port, AssetManager assets) throws IOException {
            super("127.0.0.1", port);
            this.assets = assets;
            Log.d(TAG2, "Constructed LocalAssetsServer for port " + port);
            try{ CrashLogger.i("Constructed LocalAssetsServer for port " + port); }catch(Throwable ignored){}
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

            // --- Proxy endpoint: /_proxy?u=<encodedURL> ---
            if (path.startsWith("_proxy")) {
                Map<String, String> params = session.getParms();
                String encoded = params.get("u");
                if (encoded == null || encoded.length() == 0) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing u parameter");
                }
                String remoteUrl;
                try {
                    remoteUrl = java.net.URLDecoder.decode(encoded, "UTF-8");
                } catch (Exception e) {
                    remoteUrl = encoded;
                }
                try { CrashLogger.i("Proxying remote URL: " + remoteUrl); } catch (Throwable ignored) {}
                ProxyFetchResult pf = fetchRemoteForProxy(remoteUrl);
                if (pf == null || pf.stream == null) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found: " + remoteUrl);
                }
                Response r = newChunkedResponse(Response.Status.OK, pf.contentType, pf.stream);
                r.addHeader("Access-Control-Allow-Origin", "*");
                r.addHeader("Cache-Control", "no-cache");
                return r;
            }
            // --- end proxy ---

            try {
                // shim path (id-shim injection for index.html retained if needed)
                if ("/__shim__/id-shim.js".equals("/" + path)) {
                    InputStream in = assets.open("id-shim.js");
                    CrashLogger.i("Serving id-shim.js");
                    return newChunkedResponse(Response.Status.OK, "application/javascript", in);
                }

                // favicon handling
                if ("favicon.ico".equalsIgnoreCase(path) || "favicon.png".equalsIgnoreCase(path)) {
                    try {
                        InputStream inFav = assets.open(path);
                        CrashLogger.i("Serving favicon from assets: " + path);
                        return newChunkedResponse(Response.Status.OK, guessMime(path), inFav);
                    } catch (IOException ignored) {
                        try { CrashLogger.i("favicon not found in assets; returning inline transparent PNG"); } catch (Throwable ignored2) {}
                        final String ONE_PX_PNG_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR4nGNgYAAAAAMAAWgmWQ0AAAAASUVORK5CYII=";
                        byte[] bytes = Base64.decode(ONE_PX_PNG_BASE64, Base64.DEFAULT);
                        InputStream is = new ByteArrayInputStream(bytes);
                        return newChunkedResponse(Response.Status.OK, "image/png", is);
                    }
                }

                InputStream is = assets.open(path);

                // index.html shim injection preserved (only the id-shim injection)
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

                // serve other assets unmodified (no LOG_BRIDGE_SNIPPET injection)
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

        // --- Proxy helper types / methods inside LocalAssetsServer ---
        private static class ProxyFetchResult {
            final InputStream stream;
            final String contentType;
            ProxyFetchResult(InputStream s, String ct) { stream = s; contentType = ct; }
        }

        private ProxyFetchResult fetchRemoteForProxy(String remoteUrl) {
            java.net.HttpURLConnection conn = null;
            try {
                java.net.URL u = new java.net.URL(remoteUrl);
                conn = (java.net.HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(6000);
                conn.setInstanceFollowRedirects(true);
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    String ct = conn.getContentType();
                    if (ct == null) ct = "application/octet-stream";
                    InputStream is = conn.getInputStream();
                    return new ProxyFetchResult(is, ct);
                } else {
                    CrashLogger.i("Proxy strict fetch returned non-2xx: " + code + " for " + remoteUrl);
                }
            } catch (Throwable strictEx) {
                CrashLogger.w("Proxy strict fetch failed for " + remoteUrl + ": " + strictEx, strictEx);
            } finally {
                // do not disconnect here if we returned a stream
            }

            // permissive fallback if allowed
            try {
                boolean tryInsecure = INSECURE_HTTPS_FALLBACK;
                if (!tryInsecure) return null;

                javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
                javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[] {
                    new javax.net.ssl.X509TrustManager() {
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                        public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                    }
                };
                sc.init(null, trustAllCerts, new java.security.SecureRandom());

                javax.net.ssl.HttpsURLConnection httpsConn = (javax.net.ssl.HttpsURLConnection) new java.net.URL(remoteUrl).openConnection();
                httpsConn.setSSLSocketFactory(sc.getSocketFactory());
                httpsConn.setHostnameVerifier((hostname, session) -> true);
                httpsConn.setConnectTimeout(4000);
                httpsConn.setReadTimeout(6000);
                httpsConn.setInstanceFollowRedirects(true);
                int code2 = httpsConn.getResponseCode();
                if (code2 >= 200 && code2 < 300) {
                    String ct2 = httpsConn.getContentType();
                    if (ct2 == null) ct2 = "application/octet-stream";
                    InputStream is2 = httpsConn.getInputStream();
                    CrashLogger.i("Proxy permissive fetch success for " + remoteUrl);
                    return new ProxyFetchResult(is2, ct2);
                } else {
                    CrashLogger.i("Proxy permissive returned non-2xx: " + code2 + " for " + remoteUrl);
                }
            } catch (Throwable insecureEx) {
                CrashLogger.w("Proxy permissive fetch failed for " + remoteUrl, insecureEx);
            }
            return null;
        }

        // utility: guess mime type
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
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString(enc);
        }
    } // end LocalAssetsServer

    /**
     * Try fetching https:// version of a given http:// URL.
     * Returns a WebResourceResponse if successful (HTTP 2xx), otherwise null.
     *
     * Enhanced behavior:
     * - Try default strict HTTPS first.
     * - If that fails with certificate errors and INSECURE_HTTPS_FALLBACK is enabled,
     *   and host matches whitelist, do a permissive TLS connection (trust-all) as a last resort.
     */
    private WebResourceResponse tryFetchHttpsFallback(String url) {
        if (url == null || !url.startsWith("http://")) return null;
        String httpsUrl = "https://" + url.substring(7);
        HttpURLConnection conn = null;
        try {
            // Strict attempt using system trust store
            URL u = new URL(httpsUrl);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                String contentType = conn.getContentType();
                if (contentType == null) contentType = "application/octet-stream";
                InputStream is = conn.getInputStream();
                return new WebResourceResponse(contentType, "UTF-8", is);
            } else {
                try { CrashLogger.i("HTTPS fallback returned non-2xx for " + httpsUrl + " code=" + code); } catch (Throwable ignored) {}
            }
            return null;
        } catch (Exception strictEx) {
            // Strict attempt failed — log and possibly try permissive fallback
            try { CrashLogger.w("HTTPS strict attempt failed for " + httpsUrl + ": " + strictEx, strictEx); } catch (Throwable ignored) {}
            Log.i(TAG, "HTTPS strict attempt failed for " + httpsUrl + " -> " + strictEx);
            // Decide whether to attempt permissive fallback
            try {
                URL u = new URL(httpsUrl);
                String host = u.getHost();
                boolean tryInsecure = INSECURE_HTTPS_FALLBACK;
                if (HTTPS_WHITELIST_SUFFIXES != null && HTTPS_WHITELIST_SUFFIXES.length > 0) {
                    tryInsecure = false;
                    for (String suf : HTTPS_WHITELIST_SUFFIXES) {
                        if (host != null && host.endsWith(suf)) { tryInsecure = true; break; }
                    }
                }
                if (!tryInsecure) {
                    return null;
                }

                // permissive SSLContext (trust-all) — INSECURE, use only for development/whitelist
                SSLContext sc = SSLContext.getInstance("TLS");
                TrustManager[] trustAllCerts = new TrustManager[]{
                        new X509TrustManager() {
                            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                            public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                            public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                        }
                };
                sc.init(null, trustAllCerts, new SecureRandom());

                HttpsURLConnection httpsConn = (HttpsURLConnection) new URL(httpsUrl).openConnection();
                httpsConn.setSSLSocketFactory(sc.getSocketFactory());
                httpsConn.setHostnameVerifier((hostname, session) -> true);
                httpsConn.setConnectTimeout(4000);
                httpsConn.setReadTimeout(6000);
                httpsConn.setInstanceFollowRedirects(true);
                int code2 = httpsConn.getResponseCode();
                if (code2 >= 200 && code2 < 300) {
                    String contentType = httpsConn.getContentType();
                    if (contentType == null) contentType = "application/octet-stream";
                    InputStream is2 = httpsConn.getInputStream();
                    Log.i(TAG, "HTTPS insecure fallback success for " + httpsUrl);
                    try { CrashLogger.i("HTTPS insecure fallback success for " + httpsUrl); } catch (Throwable ignored) {}
                    return new WebResourceResponse(contentType, "UTF-8", is2);
                } else {
                    try { CrashLogger.i("HTTPS insecure fallback returned non-2xx for " + httpsUrl + " code=" + code2); } catch (Throwable ignored) {}
                }
            } catch (Throwable insecureEx) {
                try { CrashLogger.w("HTTPS insecure fallback failed for " + httpsUrl, insecureEx); } catch (Throwable ignored) {}
                Log.w(TAG, "HTTPS insecure fallback failed for " + httpsUrl, insecureEx);
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    // rest of LauncherActivity unchanged...
}
