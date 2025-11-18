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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
 * - Adds retry, caching and ensures proxy uses combined CA when possible.
 */
// Insert this method into the LauncherActivity class (before the LocalAssetsServer inner class).
private int findFreePort() throws IOException {
    // Bind to loopback only to avoid exposing port externally
    java.net.InetAddress loopback = java.net.InetAddress.getByName("127.0.0.1");
    try (ServerSocket socket = new ServerSocket(0, 0, loopback)) {
        socket.setReuseAddress(true);
        return socket.getLocalPort();
    }
}
// Insert this method inside the LauncherActivity class (after onDestroy(), before LocalAssetsServer)
private int findFreePort() throws IOException {
    // Bind to loopback only to avoid exposing port externally
    java.net.InetAddress loopback = java.net.InetAddress.getByName("127.0.0.1");
    try (ServerSocket socket = new ServerSocket(0, 0, loopback)) {
        socket.setReuseAddress(true);
        return socket.getLocalPort();
    }
}
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

    // Cache directory name under app files
    private static final String REMOTE_CACHE_DIR = "remote_cache";

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

            // shouldInterceptRequest (API21+ version forwards headers)
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request){
                String url = request.getUrl().toString();
                Map<String, String> reqHeaders = request.getRequestHeaders(); // contains Range etc.
                try { CrashLogger.i("shouldInterceptRequest: "+url); } catch (Throwable ignored) {}
                return tryServeAssetForUrl(url, reqHeaders);
            }
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url){
                try { CrashLogger.i("shouldInterceptRequest: "+url); } catch (Throwable ignored) {}
                return tryServeAssetForUrl(url, Collections.emptyMap());
            }

            /**
             * Try to serve local asset (js), otherwise let app fetch and return secure content to WebView.
             * This avoids WebView issuing cleartext requests and handles custom CA validation.
             */
            private WebResourceResponse tryServeAssetForUrl(String url, Map<String, String> requestHeaders){
                try{
                    if (url == null) return null;
                    String lower = url.toLowerCase(Locale.ROOT);
                    String urlNoQuery = url.split("\\?")[0].split("#")[0];

                    // Local assets for JS first
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

                    // Local server passthrough
                    if (lower.startsWith("http://localhost:") || lower.startsWith("https://localhost:") ||
                        lower.startsWith("http://127.0.0.1:") || lower.startsWith("https://127.0.0.1:")) {
                        return null;
                    }

                    // External requests: app-level fetch with retries + cache
                    if (lower.startsWith("http://") || lower.startsWith("https://")) {
                        WebResourceResponse resp = fetchWithRetriesAndCache(url, requestHeaders);
                        if (resp != null) {
                            try { CrashLogger.i("Served remote resource via app-fetch: " + url); } catch (Throwable ignored) {}
                            return resp;
                        } else {
                            // If original was http and we couldn't fetch https, block cleartext retry
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

    // ---------- helpers: retry + cache + candidate hosts ----------
    private WebResourceResponse fetchWithRetriesAndCache(String origUrl, Map<String,String> requestHeaders) {
        // 1) try cache
        try {
            File cacheDir = new File(getFilesDir(), REMOTE_CACHE_DIR);
            if (!cacheDir.exists()) cacheDir.mkdirs();
            String cacheName = cacheFileNameForUrl(origUrl);
            File f = new File(cacheDir, cacheName);
            if (f.exists() && f.length() > 0) {
                try (FileInputStream fis = new FileInputStream(f)) {
                    String mime = guessMimeFromUrl(origUrl);
                    Map<String,String> headers = Collections.singletonMap("Access-Control-Allow-Origin","*");
                    if (Build.VERSION.SDK_INT >= 21) {
                        return new WebResourceResponse(mime, "UTF-8", 200, "OK", headers, fis);
                    } else {
                        return new WebResourceResponse(mime, "UTF-8", fis);
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 2) build candidate URLs (try https for http origins first and fallbacks for known patterns)
        List<String> candidates = new ArrayList<>();
        boolean origWasHttp = origUrl.startsWith("http://");
        if (origWasHttp) {
            candidates.add("https://" + origUrl.substring(7));
        } else {
            candidates.add(origUrl);
        }
        // Add common S3 fallback host for region issues
        if (origUrl.contains("s3-us-east-1.amazonaws.com") || origUrl.contains("s3.us-east-1.amazonaws.com")) {
            String alt = origUrl.replaceFirst("s3[.-]us-east-1\\.amazonaws\\.com", "s3.amazonaws.com");
            if (!candidates.contains(alt)) candidates.add(alt);
        }

        // 3) attempts: for each candidate try strict -> combined CA -> permissive (if enabled); retry per candidate
        int attemptsPerCandidate = 2;
        for (String tryUrl : candidates) {
            for (int attempt = 0; attempt < attemptsPerCandidate; attempt++) {
                // strict
                WebResourceResponse r = fetchUrlStrict(tryUrl, requestHeaders);
                if (r != null) {
                    // cache if reasonable (small non-streamed resources like JSON or small files)
                    tryCacheResponseIfApplicable(r, origUrl);
                    return r;
                }
                // combined CA
                WebResourceResponse r2 = fetchUrlWithCombinedCAs(tryUrl, requestHeaders);
                if (r2 != null) {
                    tryCacheResponseIfApplicable(r2, origUrl);
                    return r2;
                }
                // permissive trust-all fallback
                if (INSECURE_HTTPS_FALLBACK) {
                    WebResourceResponse r3 = fetchUrlPermissiveTrustAll(tryUrl, requestHeaders);
                    if (r3 != null) {
                        tryCacheResponseIfApplicable(r3, origUrl);
                        return r3;
                    }
                }
                // small backoff
                try { Thread.sleep(200L * (attempt + 1)); } catch (InterruptedException ignored) {}
            }
        }
        return null;
    }

    private String cacheFileNameForUrl(String url) {
        byte[] b = url.getBytes(StandardCharsets.UTF_8);
        String enc = Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        return "cache_" + enc;
    }

    private void tryCacheResponseIfApplicable(WebResourceResponse resp, String origUrl) {
        // Only cache small JSON / JS / text content to avoid storing big media files
        try {
            String mime = resp.getMimeType() != null ? resp.getMimeType() : guessMimeFromUrl(origUrl);
            if (mime.startsWith("application/json") || mime.startsWith("application/javascript") || mime.startsWith("text/")) {
                // copy stream into file
                File cacheDir = new File(getFilesDir(), REMOTE_CACHE_DIR);
                if (!cacheDir.exists()) cacheDir.mkdirs();
                File f = new File(cacheDir, cacheFileNameForUrl(origUrl));
                try (InputStream in = resp.getData();
                     FileOutputStream fos = new FileOutputStream(f)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                    fos.flush();
                }
            }
        } catch (Throwable t) {
            try { CrashLogger.w("Cache write failed: " + t, t); } catch (Throwable ignored) {}
        }
    }

    private String guessMimeFromUrl(String url) {
        try {
            String path = new URL(url).getPath();
            int idx = path.lastIndexOf('/');
            String name = idx >= 0 ? path.substring(idx + 1) : path;
            return LocalAssetsServer.guessMimeStatic(name);
        } catch (Exception e) {
            return "application/octet-stream";
        }
    }

    // ---------- helpers: strict/combined/permissive fetch implemented earlier but used via fetchWithRetriesAndCache ----------
    private WebResourceResponse fetchUrlStrict(String urlStr, Map<String,String> requestHeaders) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(urlStr);
            conn = (HttpURLConnection) u.openConnection();
            // forward headers (Range etc.)
            if (requestHeaders != null) {
                for (Map.Entry<String,String> e : requestHeaders.entrySet()) {
                    String k = e.getKey();
                    String v = e.getValue();
                    if (k == null || v == null) continue;
                    // avoid altering host / connection
                    conn.setRequestProperty(k, v);
                }
            }
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) return null;
            String contentType = conn.getContentType();
            if (contentType == null) contentType = "application/octet-stream";

            Map<String,String> respHeaders = new HashMap<>();
            for (Map.Entry<String, List<String>> hh : conn.getHeaderFields().entrySet()) {
                String hk = hh.getKey();
                if (hk == null) continue;
                List<String> vals = hh.getValue();
                if (vals == null || vals.isEmpty()) continue;
                respHeaders.put(hk, String.join(", ", vals));
            }
            if (!respHeaders.containsKey("Access-Control-Allow-Origin")) respHeaders.put("Access-Control-Allow-Origin", "*");

            if (Build.VERSION.SDK_INT >= 21) {
                String reason = conn.getResponseMessage() != null ? conn.getResponseMessage() : "OK";
                return new WebResourceResponse(contentType, "UTF-8", code, reason, respHeaders, is);
            } else {
                return new WebResourceResponse(contentType, "UTF-8", is);
            }
        } catch (Throwable t) {
            try { CrashLogger.w("Strict fetch failed for " + urlStr + ": " + t, t); } catch (Throwable ignored) {}
        } finally {
            // Do not disconnect here if we returned a stream; otherwise free
        }
        return null;
    }

    private WebResourceResponse fetchUrlWithCombinedCAs(String urlStr, Map<String,String> requestHeaders) {
        try {
            X509TrustManager combined = createCombinedTrustManagerFromAssets(); // instance method uses getAssets()
            if (combined == null) return null;
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{ combined }, new SecureRandom());

            URL u = new URL(urlStr);
            HttpsURLConnection httpsConn = (HttpsURLConnection) u.openConnection();
            httpsConn.setSSLSocketFactory(sc.getSocketFactory());
            httpsConn.setHostnameVerifier((hostname, session) -> true);

            if (requestHeaders != null) {
                for (Map.Entry<String,String> e : requestHeaders.entrySet()) {
                    String k = e.getKey();
                    String v = e.getValue();
                    if (k == null || v == null) continue;
                    httpsConn.setRequestProperty(k, v);
                }
            }

            httpsConn.setConnectTimeout(8000);
            httpsConn.setReadTimeout(10000);
            httpsConn.setInstanceFollowRedirects(true);
            int code2 = httpsConn.getResponseCode();
            InputStream is2 = (code2 >= 400) ? httpsConn.getErrorStream() : httpsConn.getInputStream();
            if (is2 == null) return null;
            String ct2 = httpsConn.getContentType();
            if (ct2 == null) ct2 = "application/octet-stream";
            Map<String,String> headers = new HashMap<>();
            for (Map.Entry<String, List<String>> hh : httpsConn.getHeaderFields().entrySet()) {
                String hk = hh.getKey();
                if (hk == null) continue;
                List<String> vals = hh.getValue();
                if (vals == null || vals.isEmpty()) continue;
                headers.put(hk, String.join(", ", vals));
            }
            if (!headers.containsKey("Access-Control-Allow-Origin")) headers.put("Access-Control-Allow-Origin", "*");
            if (Build.VERSION.SDK_INT >= 21) {
                return new WebResourceResponse(ct2, "UTF-8", code2, httpsConn.getResponseMessage(), headers, is2);
            } else {
                return new WebResourceResponse(ct2, "UTF-8", is2);
            }
        } catch (Throwable t) {
            try { CrashLogger.w("Combined CA fetch failed for " + urlStr + ": " + t, t); } catch (Throwable ignored) {}
        }
        return null;
    }

    private WebResourceResponse fetchUrlPermissiveTrustAll(String urlStr, Map<String,String> requestHeaders) {
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

            if (requestHeaders != null) {
                for (Map.Entry<String,String> e : requestHeaders.entrySet()) {
                    String k = e.getKey();
                    String v = e.getValue();
                    if (k == null || v == null) continue;
                    httpsConn.setRequestProperty(k, v);
                }
            }

            httpsConn.setConnectTimeout(8000);
            httpsConn.setReadTimeout(10000);
            httpsConn.setInstanceFollowRedirects(true);
            int code2 = httpsConn.getResponseCode();
            InputStream is2 = (code2 >= 400) ? httpsConn.getErrorStream() : httpsConn.getInputStream();
            if (is2 == null) return null;
            String ct2 = httpsConn.getContentType();
            if (ct2 == null) ct2 = "application/octet-stream";
            Map<String,String> headers = new HashMap<>();
            for (Map.Entry<String, List<String>> hh : httpsConn.getHeaderFields().entrySet()) {
                String hk = hh.getKey();
                if (hk == null) continue;
                List<String> vals = hh.getValue();
                if (vals == null || vals.isEmpty()) continue;
                headers.put(hk, String.join(", ", vals));
            }
            if (!headers.containsKey("Access-Control-Allow-Origin")) headers.put("Access-Control-Allow-Origin", "*");
            if (Build.VERSION.SDK_INT >= 21) {
                return new WebResourceResponse(ct2, "UTF-8", code2, httpsConn.getResponseMessage(), headers, is2);
            } else {
                return new WebResourceResponse(ct2, "UTF-8", is2);
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

    // tryFetchHttpsFallback kept for compatibility; now delegates to fetchWithRetriesAndCache with empty headers
    private WebResourceResponse tryFetchHttpsFallback(String url) {
        return fetchWithRetriesAndCache(url, Collections.emptyMap());
    }

    // rest of class...
    // Note: LocalAssetsServer.guessMimeStatic used by guessMimeFromUrl
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

         // Collect incoming request headers to forward (Range, Accept-Encoding, Origin, Referer, etc.)
         Map<String, String> incoming = session.getHeaders() != null ? session.getHeaders() : Collections.emptyMap();

         ProxyFetchResult pf = fetchRemoteForProxy(remoteUrl, incoming);
                if (pf == null || pf.stream == null) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found: " + remoteUrl);
                }

         // Create response with remote status and headers
         Response.Status status = Response.Status.lookup(pf.statusCode);
         if (status == null) status = Response.Status.OK; // fallback if unknown
         Response r = newChunkedResponse(status, pf.contentType != null ? pf.contentType : "application/octet-stream", pf.stream);

         // Copy remote headers to local response, but avoid duplicate/forbidden headers
         if (pf.headers != null) {
             for (Map.Entry<String, String> he : pf.headers.entrySet()) {
                 String hk = he.getKey();
                 String hv = he.getValue();
                 if (hk == null || hv == null) continue;
                 // NanoHTTPD will handle common headers; we add them so browser sees them.
                 // Avoid Content-Length because chunked response manages length.
                 if ("Content-Length".equalsIgnoreCase(hk)) continue;
                 r.addHeader(hk, hv);
                    }
                }

         // Ensure CORS present
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
                        return newChunkedResponse(Response.Status.OK, guessMimeStatic(path), inFav);
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
                    Response r2 = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html);
                    r2.addHeader("Access-Control-Allow-Origin", "*");
                    r2.addHeader("Cache-Control", "no-cache");
                    return r2;
                }

                String mime = guessMimeStatic(path);
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
            final int statusCode;
            final Map<String, String> headers;
            ProxyFetchResult(InputStream s, String ct, int code, Map<String,String> hdrs) { stream = s; contentType = ct; statusCode = code; headers = hdrs; }
        }

        // Fetch remote utility used by proxy: forwards incoming headers and returns status+headers+stream
        private ProxyFetchResult fetchRemoteForProxy(String remoteUrl, Map<String, String> incomingRequestHeaders) {
            // 1) Try strict HTTP(S) first (system trust)
            try {
                java.net.URL u = new java.net.URL(remoteUrl);
                java.net.URLConnection connRaw = u.openConnection();
                if (!(connRaw instanceof java.net.HttpURLConnection)) {
                    // Not HTTP? fallback to generic stream
                    InputStream is = connRaw.getInputStream();
                    Map<String,String> hdrsEmpty = Collections.emptyMap();
                    return new ProxyFetchResult(is, connRaw.getContentType(), 200, hdrsEmpty);
                }
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) connRaw;
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(10000);
                conn.setInstanceFollowRedirects(true);

                // Forward useful headers from incoming request (Range, Accept, Accept-Encoding, Origin, Referer, User-Agent...)
                if (incomingRequestHeaders != null) {
                    for (Map.Entry<String, String> e : incomingRequestHeaders.entrySet()) {
                        String k = e.getKey();
                        String v = e.getValue();
                        if (k == null || v == null) continue;
                        // Do not set Host/Connection which are managed by URLConnection
                        if ("host".equalsIgnoreCase(k) || "connection".equalsIgnoreCase(k)) continue;
                        conn.setRequestProperty(k, v);
                    }
                }

                int code = conn.getResponseCode();
                InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
                if (is == null) return null;
                String ct = conn.getContentType();
                Map<String,String> remoteHeaders = copyHeadersFromConnection(conn);

                return new ProxyFetchResult(is, ct, code, remoteHeaders);
            } catch (Throwable strictEx) {
                CrashLogger.w("Proxy strict fetch failed for " + remoteUrl + ": " + strictEx, strictEx);
            }

            // 2) Try combined CA fallback (if custom certs present)
            try {
                X509TrustManager combined = createCombinedTrustManagerFromAssetsLocal(); // implemented to use this.assets
                if (combined != null) {
                    SSLContext sc = SSLContext.getInstance("TLS");
                    sc.init(null, new javax.net.ssl.TrustManager[]{ combined }, new java.security.SecureRandom());
                    javax.net.ssl.HttpsURLConnection httpsConn = (javax.net.ssl.HttpsURLConnection) new java.net.URL(remoteUrl).openConnection();
                    httpsConn.setSSLSocketFactory(sc.getSocketFactory());
                    httpsConn.setHostnameVerifier((hostname, session) -> true);
                    httpsConn.setConnectTimeout(8000);
                    httpsConn.setReadTimeout(10000);
                    httpsConn.setInstanceFollowRedirects(true);
                    // forward headers
                    if (incomingRequestHeaders != null) {
                        for (Map.Entry<String,String> e : incomingRequestHeaders.entrySet()) {
                            String k = e.getKey();
                            String v = e.getValue();
                            if (k == null || v == null) continue;
                            if ("host".equalsIgnoreCase(k) || "connection".equalsIgnoreCase(k)) continue;
                            httpsConn.setRequestProperty(k, v);
                        }
                    }
                    int code2 = httpsConn.getResponseCode();
                    InputStream is2 = (code2 >= 400) ? httpsConn.getErrorStream() : httpsConn.getInputStream();
                    if (is2 == null) return null;
                    String ct2 = httpsConn.getContentType();
                    Map<String,String> remoteHeaders2 = copyHeadersFromConnection(httpsConn);
                    return new ProxyFetchResult(is2, ct2, code2, remoteHeaders2);
                }
            } catch (Throwable ex) {
                CrashLogger.w("Proxy combined-CA fetch failed for " + remoteUrl, ex);
            }

            // 3) Fallback trust-all (if enabled)
            if (INSECURE_HTTPS_FALLBACK) {
                try {
                    SSLContext sc = SSLContext.getInstance("TLS");
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
                    httpsConn.setConnectTimeout(8000);
                    httpsConn.setReadTimeout(10000);
                    httpsConn.setInstanceFollowRedirects(true);
                    if (incomingRequestHeaders != null) {
                        for (Map.Entry<String,String> e : incomingRequestHeaders.entrySet()) {
                            String k = e.getKey();
                            String v = e.getValue();
                            if (k == null || v == null) continue;
                            if ("host".equalsIgnoreCase(k) || "connection".equalsIgnoreCase(k)) continue;
                            httpsConn.setRequestProperty(k, v);
                        }
                    }
                    int code3 = httpsConn.getResponseCode();
                    InputStream is3 = (code3 >= 400) ? httpsConn.getErrorStream() : httpsConn.getInputStream();
                    if (is3 == null) return null;
                    String ct3 = httpsConn.getContentType();
                    Map<String,String> remoteHeaders3 = copyHeadersFromConnection(httpsConn);
                    return new ProxyFetchResult(is3, ct3, code3, remoteHeaders3);
                } catch (Throwable insecureEx) {
                    CrashLogger.w("Proxy permissive fetch failed for " + remoteUrl, insecureEx);
                }
            }

            return null;
        }

        // Copy response headers from HttpURLConnection into a simple Map (joining multiple values)
        private static Map<String,String> copyHeadersFromConnection(java.net.HttpURLConnection conn) {
            Map<String, String> map = new HashMap<>();
            for (Map.Entry<String, List<String>> hh : conn.getHeaderFields().entrySet()) {
                String hk = hh.getKey();
                if (hk == null) continue;
                List<String> vals = hh.getValue();
                if (vals == null || vals.isEmpty()) continue;
                map.put(hk, String.join(", ", vals));
            }
            return map;
        }

        // create combined trust manager using this.assets
        private X509TrustManager createCombinedTrustManagerFromAssetsLocal() {
            try {
                // 1) system TM
                TrustManagerFactory systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                systemTmf.init((KeyStore) null);
                X509TrustManager systemTm = null;
                for (TrustManager tm : systemTmf.getTrustManagers()) {
                    if (tm instanceof X509TrustManager) { systemTm = (X509TrustManager) tm; break; }
                }

                // 2) load custom CA certs from this.assets/certs
                String[] certFiles = null;
                try { certFiles = assets.list("certs"); } catch (IOException ioe) { certFiles = null; }
                if (certFiles == null || certFiles.length == 0) return null;

                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
                ks.load(null, null);
                int idx = 0;
                int loaded = 0;
                for (String fname : certFiles) {
                    if (fname == null || fname.trim().isEmpty()) continue;
                    InputStream in = null;
                    try {
                        in = assets.open("certs/" + fname);
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

                TrustManagerFactory customTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                customTmf.init(ks);
                X509TrustManager customTm = null;
                for (TrustManager tm : customTmf.getTrustManagers()) {
                    if (tm instanceof X509TrustManager) { customTm = (X509TrustManager) tm; break; }
                }

                final X509TrustManager sys = systemTm;
                final X509TrustManager cus = customTm;

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
                try { CrashLogger.i("LocalAssetsServer: Using combined trust managers with " + loaded + " custom CA(s)"); } catch (Throwable ignored) {}
                return combined;
            } catch (Throwable t) {
                try { CrashLogger.w("LocalAssetsServer.createCombinedTrustManagerFromAssetsLocal failed: " + t, t); } catch (Throwable ignored) {}
                return null;
            }
        }

        // static helper for mime guessing
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

        private static String readAll(InputStream in, String enc) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString(enc);
        }
    } // end LocalAssetsServer
}
