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

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * LauncherActivity
 *
 * - Starts a small local HTTP server that serves assets (NanoHTTPD)
 * - Logs important events to CrashLogger
 * - Loads http://localhost:PORT/ when server available; falls back to file:///android_asset/gjw.html
 * - Intercepts http(s) requests and prefers secure fetch with support for custom CA bundles in assets/certs/
 */
public class LauncherActivity extends AppCompatActivity {
    private static final String TAG = "LauncherActivity";
    private WebView webView;
    private LocalAssetsServer server;
    private int serverPort = -1;

    // debounce for launching player
    private volatile String lastLaunchedUrl = null;
    private volatile long lastLaunchTs = 0;
    private static final long LAUNCH_DEBOUNCE_MS = 1500;

    // HTTPS fallback settings (keep per your note)
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

            /**
             * Try to serve local asset (js), otherwise let app fetch and return secure content to WebView.
             * This avoids WebView issuing cleartext requests and handles custom CA validation.
             */
            private WebResourceResponse tryServeAssetForUrl(String url){
                try{
                    if (url == null) return null;
                    String lower = url.toLowerCase(Locale.ROOT);
                    String urlNoQuery = url.split("\\?")[0].split("#")[0];

                    // If JS requested, check assets first
                    if (lower.endsWith(".js")) {
                        int idx = urlNoQuery.lastIndexOf('/');
                        String filename = idx >= 0 ? urlNoQuery.substring(idx + 1) : urlNoQuery;
                        Log.d(TAG, "Intercept request for JS: " + url + " -> " + filename);
                        try { CrashLogger.i("Intercept request for JS: " + url + " -> " + filename); } catch (Throwable ignored) {}
                        InputStream is = null;
                        try {
                            is = getAssets().open(filename);
                        } catch (IOException ignored) {
                            try {
                                is = getAssets().open("js/" + filename);
                            } catch (IOException ignored2) {
                                is = null;
                            }
                        }
                        if (is != null) {
                            Log.i(TAG, "Serving JS from assets: " + filename);
                            return new WebResourceResponse("application/javascript", "UTF-8", is);
                        } else {
                            missingAssets.add(filename);
                            Log.d(TAG, "Asset not found for " + filename);
                        }
                    }

                    // Let LocalAssetsServer handle localhost
                    if (lower.startsWith("http://localhost:") || lower.startsWith("http://127.0.0.1:") ||
                        lower.startsWith("https://localhost:") || lower.startsWith("https://127.0.0.1:")) {
                        return null;
                    }

                    // For external http/https: app will fetch and return resource stream to WebView
                    if (lower.startsWith("http://") || lower.startsWith("https://")) {
                        WebResourceResponse resp = fetchRemoteAsWebResource(url);
                        if (resp != null) {
                            try { CrashLogger.i("Served remote resource via app-fetch: " + url); } catch (Throwable ignored) {}
                            return resp;
                        } else {
                            // If it's http and we couldn't fetch https, block cleartext retry
                            if (lower.startsWith("http://")) {
                                try { CrashLogger.i("Blocking cleartext request for " + url); } catch (Throwable ignored) {}
                                if (Build.VERSION.SDK_INT >= 21) {
                                    Map<String,String> headers = Collections.singletonMap("Content-Type","text/plain");
                                    return new WebResourceResponse("text/plain","UTF-8",204,"No Content",headers,new ByteArrayInputStream(new byte[0]));
                                } else {
                                    return new WebResourceResponse("text/plain","UTF-8", new ByteArrayInputStream(new byte[0]));
                                }
                            }
                        }
                    }
                }catch(Throwable t){
                    Log.w(TAG,"tryServeAssetForUrl failed for "+url,t);
                    try{ CrashLogger.w("tryServeAssetForUrl failed for "+url,t);}catch(Throwable ignored){}
                }
                return null;
            }

            // error handlers unchanged...
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

    // ---------- helpers ----------
    private void tryLaunchPlayerIfMedia(final String url) {
        if (url == null) return;
        String lower = url.toLowerCase(Locale.ROOT);
        boolean looksLikeMedia = lower.endsWith(".m3u8") || lower.endsWith(".mp4") || lower.endsWith(".webm") ||
                lower.endsWith(".m4a") || lower.endsWith(".aac");
        if (!looksLikeMedia) return;

        final long now = System.currentTimeMillis();
        if (url.equals(lastLaunchedUrl) && (now - lastLaunchTs) < LAUNCH_DEBOUNCE_MS) {
            return;
        }
        lastLaunchedUrl = url;
        lastLaunchTs = now;

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try { CrashLogger.i("Launching player for " + url); } catch (Throwable ignored) {}
                try {
                    Intent i = PlayerActivity.createIntent(LauncherActivity.this, Uri.parse(url));
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

    // LocalAssetsServer (keeps asset serving and optional proxy endpoint)
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

            // optional proxy endpoint (kept for compatibility)
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

            try {
                if ("/__shim__/id-shim.js".equals("/" + path)) {
                    InputStream in = assets.open("id-shim.js");
                    CrashLogger.i("Serving id-shim.js");
                    return newChunkedResponse(Response.Status.OK, "application/javascript", in);
                }

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

        // Proxy helpers
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

                // try combined trust manager from assets (this method belongs to outer class; cannot call here static)
                // For simplicity, in proxy fallback we use trust-all as before (kept minimal). If you prefer combined CA here too,
                // move combined-trust creation logic into a shared util accessible from static context or pass required params.
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
     * Fetch remote URL and return a WebResourceResponse (used in shouldInterceptRequest).
     * Strategy:
     *  - If original is http://, try https:// first (strict), then attempt permissive/combined CA fallback.
     *  - If original is https://, try strict then permissive/combined CA fallback.
     */
    private WebResourceResponse fetchRemoteAsWebResource(String origUrl) {
        if (origUrl == null) return null;
        try {
            String tryUrl = origUrl;
            boolean wasHttp = false;
            if (origUrl.startsWith("http://")) {
                tryUrl = "https://" + origUrl.substring(7);
                wasHttp = true;
            }

            // 1) Strict attempt
            WebResourceResponse strict = fetchUrlStrict(tryUrl);
            if (strict != null) return strict;

            // 2) Combined CA permissive attempt (system + assets/certs)
            WebResourceResponse combined = fetchUrlWithCombinedCAs(tryUrl);
            if (combined != null) return combined;

            // 3) Legacy permissive trust-all (last resort, controlled by INSECURE_HTTPS_FALLBACK)
            if (INSECURE_HTTPS_FALLBACK) {
                WebResourceResponse permissive = fetchUrlPermissiveTrustAll(tryUrl);
                if (permissive != null) return permissive;
            }

            // If original was http and none of the https attempts succeeded, do not return http content (block cleartext)
            // Returning null causes WebView to proceed with its default behavior which will be blocked; earlier logic returns 204 to block.
        } catch (Throwable t) {
            Log.w(TAG, "fetchRemoteAsWebResource failed for " + origUrl, t);
            try { CrashLogger.w("fetchRemoteAsWebResource failed for " + origUrl, t); } catch (Throwable ignored) {}
        }
        return null;
    }

    private WebResourceResponse fetchUrlStrict(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(urlStr);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                String ct = conn.getContentType();
                if (ct == null) ct = "application/octet-stream";
                InputStream is = conn.getInputStream();
                Map<String,String> headers = new HashMap<>();
                headers.put("Access-Control-Allow-Origin", "*");
                if (Build.VERSION.SDK_INT >= 21) {
                    return new WebResourceResponse(ct, "UTF-8", code, "OK", headers, is);
                } else {
                    return new WebResourceResponse(ct, "UTF-8", is);
                }
            }
        } catch (Throwable t) {
            try { CrashLogger.w("Strict fetch failed for " + urlStr + ": " + t, t); } catch (Throwable ignored) {}
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    /**
     * Attempt to fetch using a combined trust manager that tries system CA first, then custom CAs from assets/certs/.
     * Returns null if combined TM unavailable or fetch failed.
     */
    private WebResourceResponse fetchUrlWithCombinedCAs(String urlStr) {
        try {
            X509TrustManager combined = createCombinedTrustManagerFromAssets();
            if (combined == null) return null;

            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{ combined }, new SecureRandom());

            URL u = new URL(urlStr);
            HttpsURLConnection httpsConn = (HttpsURLConnection) u.openConnection();
            httpsConn.setSSLSocketFactory(sc.getSocketFactory());
            httpsConn.setHostnameVerifier((hostname, session) -> true); // you can tighten hostname verification if desired
            httpsConn.setConnectTimeout(4000);
            httpsConn.setReadTimeout(6000);
            httpsConn.setInstanceFollowRedirects(true);
            int code2 = httpsConn.getResponseCode();
            if (code2 >= 200 && code2 < 300) {
                String ct2 = httpsConn.getContentType();
                if (ct2 == null) ct2 = "application/octet-stream";
                InputStream is2 = httpsConn.getInputStream();
                Map<String,String> headers = new HashMap<>();
                headers.put("Access-Control-Allow-Origin", "*");
                if (Build.VERSION.SDK_INT >= 21) {
                    return new WebResourceResponse(ct2, "UTF-8", code2, "OK", headers, is2);
                } else {
                    return new WebResourceResponse(ct2, "UTF-8", is2);
                }
            } else {
                try { CrashLogger.i("Combined CA fetch returned non-2xx: " + code2 + " for " + urlStr); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            try { CrashLogger.w("Combined CA fetch failed for " + urlStr + ": " + t, t); } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * Legacy permissive trust-all fetch used as last resort (kept for compatibility).
     * Prefer fetchUrlWithCombinedCAs which uses custom CA files in assets/certs/.
     */
    private WebResourceResponse fetchUrlPermissiveTrustAll(String urlStr) {
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
            };
            sc.init(null, trustAllCerts, new SecureRandom());

            URL u = new URL(urlStr);
            HttpsURLConnection httpsConn = (HttpsURLConnection) u.openConnection();
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
                Map<String,String> headers = new HashMap<>();
                headers.put("Access-Control-Allow-Origin", "*");
                if (Build.VERSION.SDK_INT >= 21) {
                    return new WebResourceResponse(ct2, "UTF-8", code2, "OK", headers, is2);
                } else {
                    return new WebResourceResponse(ct2, "UTF-8", is2);
                }
            }
        } catch (Throwable insecureEx) {
            try { CrashLogger.w("Permissive trust-all fetch failed for " + urlStr, insecureEx); } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * Create a combined X509TrustManager that tries system default first, then custom CAs loaded from assets/certs/*.pem.
     * Returns null if no custom CAs present / failed to create.
     */
    private X509TrustManager createCombinedTrustManagerFromAssets() {
        try {
            // 1) get system default TrustManager
            TrustManagerFactory systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            systemTmf.init((KeyStore) null);
            X509TrustManager systemTm = null;
            for (TrustManager tm : systemTmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) { systemTm = (X509TrustManager) tm; break; }
            }

            // 2) load custom CA certs from assets/certs/
            String[] certFiles = null;
            try {
                certFiles = getAssets().list("certs");
            } catch (IOException ioe) {
                certFiles = null;
            }
            if (certFiles == null || certFiles.length == 0) {
                // no custom CA files present
                return null;
            }

            // Build a KeyStore containing all custom CA certs
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int idx = 0;
            int loaded = 0;
            for (String fname : certFiles) {
                if (fname == null || fname.trim().isEmpty()) continue;
                InputStream in = null;
                try {
                    in = getAssets().open("certs/" + fname);
                    BufferedInputStream bis = new BufferedInputStream(in);
                    while (bis.available() > 0) {
                        Certificate cert = cf.generateCertificate(bis);
                        String alias = "ca" + (idx++);
                        ks.setCertificateEntry(alias, cert);
                        loaded++;
                    }
                } catch (Throwable e) {
                    try { CrashLogger.w("Failed to load cert " + fname + ": " + e, e); } catch (Throwable ignored) {}
                } finally {
                    try { if (in != null) in.close(); } catch (Throwable ignored) {}
                }
            }
            if (loaded == 0) return null;

            // 3) create TrustManagerFactory from KeyStore (custom CA)
            TrustManagerFactory customTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            customTmf.init(ks);
            X509TrustManager customTm = null;
            for (TrustManager tm : customTmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) { customTm = (X509TrustManager) tm; break; }
            }

            final X509TrustManager sys = systemTm;
            final X509TrustManager cus = customTm;

            // 4) composite trust manager: try system first, then custom
            X509TrustManager combined = new X509TrustManager() {
                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    List<X509Certificate> list = new ArrayList<>();
                    if (sys != null) list.addAll(Arrays.asList(sys.getAcceptedIssuers()));
                    if (cus != null) list.addAll(Arrays.asList(cus.getAcceptedIssuers()));
                    return list.toArray(new X509Certificate[list.size()]);
                }

                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    if (sys != null) {
                        try { sys.checkClientTrusted(chain, authType); return; } catch (CertificateException ignored) {}
                    }
                    if (cus != null) { cus.checkClientTrusted(chain, authType); return; }
                    throw new CertificateException("Client cert not trusted by system or custom CAs");
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    if (sys != null) {
                        try { sys.checkServerTrusted(chain, authType); return; } catch (CertificateException ignored) {}
                    }
                    if (cus != null) { cus.checkServerTrusted(chain, authType); return; }
                    throw new CertificateException("Server cert not trusted by system or custom CAs");
                }
            };

            try { CrashLogger.i("Using combined trust managers with " + loaded + " custom CA(s)"); } catch (Throwable ignored) {}
            return combined;
        } catch (Throwable t) {
            try { CrashLogger.w("createCombinedTrustManagerFromAssets failed: " + t, t); } catch (Throwable ignored) {}
            return null;
        }
    }

    // tryFetchHttpsFallback kept for compatibility; now delegates to fetchRemoteAsWebResource
    private WebResourceResponse tryFetchHttpsFallback(String url) {
        return fetchRemoteAsWebResource(url);
    }

    // rest of class...
}
