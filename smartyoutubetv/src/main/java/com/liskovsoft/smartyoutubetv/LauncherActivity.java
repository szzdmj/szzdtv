// (完整文件，修正：把清缓存逻辑移入 onCreate 内并移除文件顶部的非法 try/catch；增加 deleteRecursive 方法在类尾部)
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
import android.webkit.CookieManager;

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
import java.net.SocketException;
import java.net.URL;
import java.nio.charset.Charset;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 *
 * Changes in this revision:
 * - Loosen various runtime restrictions for debugging: accept SSL errors (optional), accept third-party cookies,
 *   avoid blocking cleartext fallback so WebView can try direct fetch, disable JS sanitization (optional),
 *   inject permissive CSP into gjw.html to allow frames/styles/scripts.
 *
 * IMPORTANT: these changes reduce security (SSL acceptance, permissive CSP). Use only for debugging/troubleshooting.
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

    // HTTPS fallback settings
    private static final boolean INSECURE_HTTPS_FALLBACK = true;
    private static final String[] HTTPS_WHITELIST_SUFFIXES = new String[] {
        "s3.amazonaws.com",
        "cloudfront.net"
    };

    // Debug / loosen flags (toggle for testing)
    private static final boolean ALLOW_ALL_SSL_ERRORS = true;          // If true, WebView SSL errors are proceeded (INSECURE)
    private static final boolean SANITIZE_JS_RESPONSES = false;       // If false, do not replace JS responses that look like HTML
    private static final boolean BLOCK_CLEARTEXT_ON_FAILURE = false;  // If false, allow WebView to try direct http if app-proxy failed

    // Networking / retry tuning
    private static final int CONNECT_TIMEOUT_MS = 180_000;
    private static final int READ_TIMEOUT_MS = 180_000;
    private static final int MAX_ATTEMPTS_PER_CANDIDATE = 4;
    private static final long RETRY_BASE_BACKOFF_MS = 500L; // multiplied by attempt index

    // Default User-Agent to present to upstream servers (helps with Cloudflare/edge)
    private static final String DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

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

        // Clear caches (best-effort). Must be after webView is instantiated.
        try {
            webView.clearCache(true);
            webView.clearHistory();
            webView.clearFormData();

            // Clear cookies
            CookieManager cm = CookieManager.getInstance();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.removeAllCookies(null);
                cm.flush();
            } else {
                cm.removeAllCookie();
            }

            // Clear WebStorage (HTML5 localStorage/sessionStorage and WebSQL)
            try { android.webkit.WebStorage.getInstance().deleteAllData(); } catch (Throwable ignored) {}

            // Delete app-level remote_cache directory (the app's own cache on getFilesDir())
            try {
                File cacheDir = new File(getFilesDir(), REMOTE_CACHE_DIR);
                if (cacheDir.exists()) deleteRecursive(cacheDir);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) { /* best-effort */ }

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

        // Enable cookies / third-party cookies to avoid missing resources that rely on cookies
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= 21) {
                cm.setAcceptThirdPartyCookies(webView, true);
            }
            try { CrashLogger.i("CookieManager: accept cookies and third-party cookies enabled"); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}

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
                    Log.e(TAG,"handleUrl failed",t); try{ CrashLogger.w("handleUrl failed",t);}catch(Throwable ignored){}
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
            public void onPageFinished(WebView view, String url) {
                try { CrashLogger.i("onPageFinished: " + url); } catch (Throwable ignored) {}

                // global window.onerror -> forward to Android bridge (so JS runtime errors appear in CrashLogger)
                try {
                    String setOnError = "window.onerror = function(msg, src, line, col, err) {" +
                            "  try { Android.log('JS_ERROR: ' + msg + ' @' + src + ':' + line + ':' + col + (err?(' stack:'+err.stack):'')); } catch(e) {};" +
                            "};";
                    view.evaluateJavascript(setOnError, null);
                } catch (Throwable ignored) {}

                // Query DOM counts (iframes, lists, nodes) and forward results to CrashLogger
                try {
                    String probe = "(function(){ try {" +
                            "var info = {" +
                            "iframes: document.getElementsByTagName('iframe').length," +
                            "lists: document.querySelectorAll('ul,ol').length," +
                            "roleLists: document.querySelectorAll('[role=\"list\"]').length," +
                            "bodyLen: document.body?document.body.innerText.length:0," +
                            "title: document.title || ''" +
                            "};" +
                            "if (window.Android && Android.log) Android.log('DOM_INFO:' + JSON.stringify(info));" +
                            "return JSON.stringify(info);" +
                            "} catch(e) { if (window.Android && Android.log) Android.log('DOM_PROBE_ERR:' + e.toString()); return 'ERR'; } })();";
                    view.evaluateJavascript(probe, new android.webkit.ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            try { CrashLogger.i("evaluateJavascript returned: " + value); } catch (Throwable ignored) {}
                        }
                    });
                } catch (Throwable ignored) {}
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url){
                try { CrashLogger.i("shouldInterceptRequest: "+url); } catch (Throwable ignored) {}
                return tryServeAssetForUrl(url, Collections.emptyMap());
            }

            private WebResourceResponse tryServeAssetForUrl(String url, Map<String, String> requestHeaders){
                try{
                    if (url == null) return null;
                    String lower = url.toLowerCase(Locale.ROOT);
                    String urlNoQuery = url.split("\\?")[0].split("#")[0];

                    // 1) Serve local JS if present in apk assets (preferable for critical scripts)
                    if (lower.endsWith(".js")) {
                        int idx = urlNoQuery.lastIndexOf('/');
                        String filename = idx >= 0 ? urlNoQuery.substring(idx + 1) : urlNoQuery;
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
                            try { CrashLogger.i("Serving JS from assets: " + filename); } catch (Throwable ignored) {}
                            return new WebResourceResponse("application/javascript", "UTF-8", is);
                        } else {
                            missingAssets.add(filename);
                            try { CrashLogger.w("Asset not found for " + filename, null); } catch (Throwable ignored) {}
                        }
                    }

                    // 2) Bypass: let WebView talk directly to local server (do NOT proxy/upgrade local requests)
                    if (lower.startsWith("http://localhost:") || lower.startsWith("https://localhost:")
                            || lower.startsWith("http://127.0.0.1:") || lower.startsWith("https://127.0.0.1:")) {
                        try { CrashLogger.i("Bypassing proxy for local request: " + url); } catch (Throwable ignored) {}
                        return null;
                    }

                    // 3) External requests via app-level fetch (with https-upgrade attempt for http)
                    if (lower.startsWith("http://") || lower.startsWith("https://")) {
                        boolean origWasHttp = lower.startsWith("http://");

                        // If the request was http -> try https upgrade first for non-local hosts
                        if (origWasHttp) {
                            String httpsUrl = "https://" + url.substring("http://".length());
                            try {
                                try { CrashLogger.i("Attempting http->https upgrade for: " + url + " -> " + httpsUrl); } catch (Throwable ignored) {}
                                WebResourceResponse httpsResp = fetchWithRetriesAndCache(httpsUrl, requestHeaders);
                                if (httpsResp != null) {
                                    try { CrashLogger.i("Upgraded http->https for " + url + " -> " + httpsUrl); } catch (Throwable ignored) {}
                                    return httpsResp;
                                } else {
                                    try { CrashLogger.i("http->https upgrade failed or no https content for: " + url); } catch (Throwable ignored) {}
                                }
                            } catch (Throwable t) {
                                try { CrashLogger.w("http->https upgrade attempt failed for " + url + ": " + t, t); } catch (Throwable ignored) {}
                                // fall-through to try original URL
                            }
                        }

                        // Try fetching original URL (either https original or http when upgrade failed)
                        WebResourceResponse resp = fetchWithRetriesAndCache(url, requestHeaders);
                        if (resp != null) {
                            try { CrashLogger.i("Served remote resource via app-fetch: " + url); } catch (Throwable ignored) {}
                            return resp;
                        } else {
                            // If original was http and nothing returned, either block cleartext (original behaviour) or allow WebView try
                            if (origWasHttp) {
                                if (BLOCK_CLEARTEXT_ON_FAILURE) {
                                    try { CrashLogger.i("Blocking cleartext request for " + url + " (no available content)"); } catch (Throwable ignored) {}
                                    if (Build.VERSION.SDK_INT >= 21) {
                                        Map<String,String> headers = Collections.singletonMap("Content-Type","text/plain");
                                        return new WebResourceResponse("text/plain","UTF-8",204,"No Content",headers,new ByteArrayInputStream(new byte[0]));
                                    } else {
                                        return new WebResourceResponse("text/plain","UTF-8", new ByteArrayInputStream(new byte[0]));
                                    }
                                } else {
                                    try { CrashLogger.i("Allowing WebView to attempt original http request for: " + url); } catch (Throwable ignored) {}
                                    return null; // let WebView do default http request (we relaxed blocking)
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
                // For debugging: optionally proceed (INSECURE)
                if (ALLOW_ALL_SSL_ERRORS) {
                    try { CrashLogger.i("Proceeding on SSL error because ALLOW_ALL_SSL_ERRORS=true"); } catch (Throwable ignored) {}
                    handler.proceed();
                } else {
                    handler.cancel();
                }
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

    // ---------- helpers: content-type parsing ----------
    private String[] splitMimeAndCharset(String contentType) {
        if (contentType == null) return new String[] { "application/octet-stream", null };
        try {
            String ct = contentType;
            int idx = ct.toLowerCase(Locale.ROOT).indexOf("charset=");
            String charset = null;
            if (idx >= 0) {
                charset = ct.substring(idx + 8).trim();
                int semi = charset.indexOf(';');
                if (semi >= 0) charset = charset.substring(0, semi).trim();
                if (charset.isEmpty()) charset = null;
                ct = ct.substring(0, idx);
                ct = ct.replaceAll("[;\\s]+$", "").trim();
            }
            return new String[] { ct.trim(), charset != null ? charset.toUpperCase(Locale.ROOT) : null };
        } catch (Throwable t) {
            return new String[] { contentType, null };
        }
    }

    // ---------- helpers: retry + cache + candidate hosts ----------
    private WebResourceResponse fetchWithRetriesAndCache(String origUrl, Map<String,String> requestHeaders) {
        // 1) try cache (small textual cached files)
        try {
            File cacheDir = new File(getFilesDir(), REMOTE_CACHE_DIR);
            if (!cacheDir.exists()) cacheDir.mkdirs();
            String cacheName = cacheFileNameForUrl(origUrl);
            File f = new File(cacheDir, cacheName);
            if (f.exists() && f.length() > 0) {
                FileInputStream fis = new FileInputStream(f);
                String guessed = guessMimeFromUrl(origUrl);
                String[] parts = splitMimeAndCharset(guessed);
                String mimeOnly = parts[0];
                String encoding = parts[1] != null ? parts[1] : chooseEncodingForMime(mimeOnly);
                Map<String,String> headers = Collections.singletonMap("Access-Control-Allow-Origin","*");
                if (Build.VERSION.SDK_INT >= 21) {
                    return new WebResourceResponse(mimeOnly, encoding, 200, "OK", headers, fis);
                } else {
                    return new WebResourceResponse(mimeOnly, encoding, fis);
                }
            }
        } catch (Throwable ignored) {}

        // 2) build candidate URLs (try https for http origins first and fallbacks for known patterns)
        List<String> candidates = new ArrayList<>();
        boolean origWasHttp = origUrl.startsWith("http://");
        if (origWasHttp) candidates.add("https://" + origUrl.substring(7)); else candidates.add(origUrl);
        if (origUrl.contains("s3-us-east-1.amazonaws.com") || origUrl.contains("s3.us-east-1.amazonaws.com")) {
            String alt = origUrl.replaceFirst("s3[.-]us-east-1\\.amazonaws\\.com", "s3.amazonaws.com");
            if (!candidates.contains(alt)) candidates.add(alt);
        }

        // 3) attempts: for each candidate try strict -> combined CA -> permissive (if allowed); retry per candidate
        for (String tryUrl : candidates) {
            for (int attempt = 0; attempt < MAX_ATTEMPTS_PER_CANDIDATE; attempt++) {
                // strict
                WebResourceResponse r = fetchUrlStrict(tryUrl, requestHeaders);
                if (r != null) {
                    WebResourceResponse safe = prepareCacheableResponseAndMaybeCache(r, origUrl);
                    if (safe != null) return safe;
                    return r;
                }

                // combined CA
                WebResourceResponse r2 = fetchUrlWithCombinedCAs(tryUrl, requestHeaders);
                if (r2 != null) {
                    WebResourceResponse safe2 = prepareCacheableResponseAndMaybeCache(r2, origUrl);
                    if (safe2 != null) return safe2;
                    return r2;
                }

                // permissive trust-all fallback only if allowed for this host
                if (INSECURE_HTTPS_FALLBACK && isPermissiveAllowedForUrl(tryUrl)) {
                    WebResourceResponse r3 = fetchUrlPermissiveTrustAll(tryUrl, requestHeaders);
                    if (r3 != null) {
                        WebResourceResponse safe3 = prepareCacheableResponseAndMaybeCache(r3, origUrl);
                        if (safe3 != null) return safe3;
                        return r3;
                    }
                }

                // small backoff (exponential-ish)
                try { Thread.sleep(RETRY_BASE_BACKOFF_MS * (attempt + 1)); } catch (InterruptedException ignored) {}
            }
        }
        return null;
    }

    // Helper to detect if data looks like HTML
    private boolean looksLikeHtml(byte[] data, Charset cs) {
        try {
            String head = new String(data, 0, Math.min(data.length, 512), cs).trim().toLowerCase(Locale.ROOT);
            return head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("<!--") || head.contains("<script") || head.contains("<html");
        } catch (Throwable t) { return false; }
    }

    // Extract redirect target from HTML preview (meta refresh or window.location or location.replace)
    private String extractRedirectFromHtml(byte[] data, Charset cs, String baseUrl) {
        try {
            String text = new String(data, 0, Math.min(data.length, 8192), cs);
            // meta refresh: <meta http-equiv='refresh' content='0;url=/path'>
            Pattern meta = Pattern.compile("(?i)<meta[^>]*http-equiv\\s*=\\s*['\"]?refresh['\"]?[^>]*content\\s*=\\s*['\"]?[^;]*;\\s*url=([^'\">]+)['\"]?", Pattern.CASE_INSENSITIVE);
            Matcher m = meta.matcher(text);
            if (m.find()) {
                String url = m.group(1).trim();
                try { return new URL(new URL(baseUrl), url).toString(); } catch (Throwable ignored) {}
            }
            // window.location = '...'; location.href='...'; location.replace('...')
            Pattern loc = Pattern.compile("(?i)location\\.(?:href|replace)\\s*[:=]\\s*['\"]([^'\"]+)['\"]");
            m = loc.matcher(text);
            if (m.find()) {
                String url = m.group(1).trim();
                try { return new URL(new URL(baseUrl), url).toString(); } catch (Throwable ignored) {}
            }
            Pattern winloc = Pattern.compile("(?i)window\\.location\\s*[:=]\\s*['\"]([^'\"]+)['\"]");
            m = winloc.matcher(text);
            if (m.find()) {
                String url = m.group(1).trim();
                try { return new URL(new URL(baseUrl), url).toString(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // Helper to check if requested URL is a JS file
    private boolean isJsRequest(String url) {
        if (url == null) return false;
        String u = url.split("\\?")[0].toLowerCase(Locale.ROOT);
        return u.endsWith(".js");
    }

    // prepareCacheableResponseAndMaybeCache: read textual stream, preview log, sanitize .js-if-html and follow simple HTML redirects once
    private WebResourceResponse prepareCacheableResponseAndMaybeCache(WebResourceResponse resp, String origUrl) {
        try {
            if (resp == null) return null;

            String incomingMime = resp.getMimeType();
            String[] parts = splitMimeAndCharset(incomingMime);
            String mimeOnly = parts[0] != null ? parts[0] : "application/octet-stream";
            String encoding = parts[1] != null ? parts[1] : chooseEncodingForMime(mimeOnly);

            boolean isTextual = mimeOnly.startsWith("application/json") || mimeOnly.startsWith("application/javascript") || mimeOnly.startsWith("text/") || mimeOnly.contains("html");
            if (!isTextual) {
                // Non-textual -> do not cache; just return resp as-is.
                return resp;
            }
            InputStream in = resp.getData();
            if (in == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream rin = in) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = rin.read(buf)) > 0) bos.write(buf, 0, n);
            }
            byte[] data = bos.toByteArray();

            // DEBUG preview
            try {
                Charset cs = (encoding != null) ? Charset.forName(encoding) : StandardCharsets.UTF_8;
                int previewLen = Math.min(data.length, 2048);
                String preview = new String(data, 0, previewLen, cs);
                String dbg = String.format("DEBUG_FETCH preview for %s (len=%d): %s",
                        origUrl, data.length, preview.replaceAll("[\\r\\n]+", " "));
                try { CrashLogger.i(dbg); } catch (Throwable ignored) { Log.i(TAG, dbg); }
            } catch (Throwable dbgEx) {
                try { CrashLogger.w("DEBUG_FETCH preview failed: " + dbgEx, dbgEx); } catch (Throwable ignored) {}
            }

            // If HTML looks like a blocking/wrapped page, attempt to extract redirect and follow once
            try {
                Charset cs = (encoding != null) ? Charset.forName(encoding) : StandardCharsets.UTF_8;
                if (looksLikeHtml(data, cs)) {
                    String follow = extractRedirectFromHtml(data, cs, origUrl);
                    if (follow != null && !follow.isEmpty()) {
                        try {
                            try { CrashLogger.i("Auto-following HTML redirect from " + origUrl + " -> " + follow); } catch (Throwable ignored) {}
                            WebResourceResponse followed = fetchWithRetriesAndCache(follow, Collections.emptyMap());
                            if (followed != null) {
                                // prepareCacheableResponseAndMaybeCache will be called recursively by caller if needed;
                                // but here we return the followed response directly.
                                try { CrashLogger.i("Auto-follow returned content for " + follow); } catch (Throwable ignored) {}
                                return followed;
                            }
                        } catch (Throwable fx) {
                            try { CrashLogger.w("Auto-follow failed: " + fx, fx); } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // If JS requested but server returned HTML (404/520 pages), optionally return safe empty JS to avoid parse error,
            // but when SANITIZE_JS_RESPONSES==false we keep original content so the site may still work (risky).
            try {
                Charset cs = (encoding != null) ? Charset.forName(encoding) : StandardCharsets.UTF_8;
                if (isJsRequest(origUrl) && (mimeOnly.contains("html") || looksLikeHtml(data, cs))) {
                    if (SANITIZE_JS_RESPONSES) {
                        String note = "/* blocked returned HTML for JS request: replaced with empty JS to avoid parse error */";
                        byte[] empty = note.getBytes(StandardCharsets.UTF_8);
                        data = empty;
                        mimeOnly = "application/javascript";
                        encoding = "UTF-8";
                        try { CrashLogger.i("Replaced unexpected HTML response with empty JS for: " + origUrl); } catch (Throwable ignored) {}
                    } else {
                        try { CrashLogger.i("SANITIZE_JS_RESPONSES=false: keeping original HTML for JS request: " + origUrl); } catch (Throwable ignored) {}
                        // keep data as-is (may produce JS parse errors, but can enable complex front-end to run)
                    }
                }
            } catch (Throwable t) {
                try { CrashLogger.w("JS sanitization failed: " + t, t); } catch (Throwable ignored) {}
            }

            // Optional http->https rewrite (unchanged)
            byte[] rewritten = rewriteHttpToHttpsIfNeeded(data, encoding, origUrl);
            if (rewritten != null) data = rewritten;

            // Write cache file (best-effort)
            try {
                File cacheDir = new File(getFilesDir(), REMOTE_CACHE_DIR);
                if (!cacheDir.exists()) cacheDir.mkdirs();
                File out = new File(cacheDir, cacheFileNameForUrl(origUrl));
                try (FileOutputStream fos = new FileOutputStream(out)) { fos.write(data); fos.flush(); }
            } catch (Throwable ce) {
                try { CrashLogger.w("Cache write failed: " + ce, ce); } catch (Throwable ignored) {}
            }

            Map<String,String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");

            ByteArrayInputStream bis = new ByteArrayInputStream(data);
            if (Build.VERSION.SDK_INT >= 21) {
                return new WebResourceResponse(mimeOnly, encoding, 200, "OK", headers, bis);
            } else {
                return new WebResourceResponse(mimeOnly, encoding, bis);
            }
        } catch (Throwable t) {
            try { CrashLogger.w("prepareCacheableResponseAndMaybeCache failed: " + t, t); } catch (Throwable ignored) {}
            return null;
        }
    }

    // Try to conservatively rewrite http://host/... to https://host/... only for allowed hosts.
    private byte[] rewriteHttpToHttpsIfNeeded(byte[] data, String encoding, String origUrl) {
        if (data == null || data.length == 0) return data;
        if (origUrl == null) return data;
        try {
            Charset cs = (encoding != null) ? Charset.forName(encoding) : StandardCharsets.UTF_8;
            String text = new String(data, cs);

            URL u = new URL(origUrl);
            String host = u.getHost();
            if (host == null || host.isEmpty()) return data;

            boolean hostAllowed = false;
            if (HTTPS_WHITELIST_SUFFIXES != null && HTTPS_WHITELIST_SUFFIXES.length > 0) {
                for (String suf : HTTPS_WHITELIST_SUFFIXES) {
                    if (suf != null && !suf.isEmpty() && host.endsWith(suf)) { hostAllowed = true; break; }
                }
            } else {
                if (host.endsWith("s3.amazonaws.com") || host.endsWith("cloudfront.net") || host.contains("localhost")) hostAllowed = true;
            }
            if (!hostAllowed) return data;

            String hostEsc = Pattern.quote(host);
            Pattern p1 = Pattern.compile("http://" + hostEsc + "(?::(\\d+))?/");
            Matcher m1 = p1.matcher(text);
            boolean any = m1.find();
            text = m1.replaceAll("https://" + host + "/");
            Pattern p2 = Pattern.compile("(?<!:)/{2}" + hostEsc + "/");
            Matcher m2 = p2.matcher(text);
            any = any || m2.find();
            text = m2.replaceAll("https://" + host + "/");
            if (any) {
                try { CrashLogger.i("Rewrote http->https for host=" + host + " url=" + origUrl); } catch (Throwable ignored) {}
            }
            return text.getBytes(cs);
        } catch (Throwable t) {
            try { CrashLogger.w("rewriteHttpToHttpsIfNeeded failed: " + t, t); } catch (Throwable ignored) {}
            return data;
        }
    }

    private String cacheFileNameForUrl(String url) {
        byte[] b = url.getBytes(StandardCharsets.UTF_8);
        String enc = Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        return "cache_" + enc;
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

    private String chooseEncodingForMime(String contentType) {
        if (contentType == null) return null;
        try {
            String lower = contentType.toLowerCase(Locale.ROOT);
            int idx = lower.indexOf("charset=");
            if (idx >= 0) {
                String cs = lower.substring(idx + 8).trim();
                int semi = cs.indexOf(';');
                if (semi >= 0) cs = cs.substring(0, semi).trim();
                if (!cs.isEmpty()) {
                    try {
                        return cs.toUpperCase(Locale.ROOT);
                    } catch (Throwable ignored) {}
                }
            }
            if (lower.startsWith("text/") || lower.contains("json") || lower.contains("javascript") || lower.contains("xml")) {
                return "UTF-8";
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // permissive allowlist (kept as before)
    private boolean isPermissiveAllowedForUrl(String url) {
        if (!INSECURE_HTTPS_FALLBACK) return false;
        try {
            URL u = new URL(url);
            String host = u.getHost();
            if (host == null) return false;
            if (HTTPS_WHITELIST_SUFFIXES != null && HTTPS_WHITELIST_SUFFIXES.length > 0) {
                for (String suf : HTTPS_WHITELIST_SUFFIXES) {
                    if (suf != null && !suf.isEmpty() && host.endsWith(suf)) return true;
                }
                return false;
            }
            if (host.endsWith(".local") || host.endsWith(".test") || host.contains("localhost") || host.contains("127.0.0.1")) return true;
            if (host.endsWith("s3.amazonaws.com") || host.endsWith("cloudfront.net")) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    // ---------- helpers: strict/combined/permissive fetch ----------
    private WebResourceResponse fetchUrlStrict(String urlStr, Map<String,String> requestHeaders) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(urlStr);
            conn = (HttpURLConnection) u.openConnection();

            // forward headers from requestHeaders and ensure UA/Accept/Accept-Encoding set
            boolean uaPresent = false;
            if (requestHeaders != null) {
                for (Map.Entry<String,String> e : requestHeaders.entrySet()) {
                    String k = e.getKey();
                    String v = e.getValue();
                    if (k == null || v == null) continue;
                    if ("host".equalsIgnoreCase(k) || "connection".equalsIgnoreCase(k)) continue;
                    conn.setRequestProperty(k, v);
                    if ("user-agent".equalsIgnoreCase(k)) uaPresent = true;
                }
            }
            if (!uaPresent) conn.setRequestProperty("User-Agent", DEFAULT_UA);
            // Avoid compressed responses that we may mishandle; ask for identity
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");

            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) return null;
            String contentType = conn.getContentType();
            if (contentType == null) contentType = "application/octet-stream";

            String[] parts = splitMimeAndCharset(contentType);
            String mimeOnly = parts[0];
            String encoding = parts[1] != null ? parts[1] : chooseEncodingForMime(mimeOnly);

            Map<String,String> respHeaders = new HashMap<>();
            for (Map.Entry<String, List<String>> hh : conn.getHeaderFields().entrySet()) {
                String hk = hh.getKey();
                if (hk == null) continue;
                List<String> vals = hh.getValue();
                if (vals == null || vals.isEmpty()) continue;
                respHeaders.put(hk, String.join(", ", vals));
            }
            if (!respHeaders.containsKey("Access-Control-Allow-Origin")) respHeaders.put("Access-Control-Allow-Origin", "*");

            try { CrashLogger.i("Strict fetch returned for " + urlStr + " code=" + code + " type=" + contentType); } catch (Throwable ignored) {}
            if (Build.VERSION.SDK_INT >= 21) {
                String reason = conn.getResponseMessage() != null ? conn.getResponseMessage() : "OK";
                return new WebResourceResponse(mimeOnly, encoding, code, reason, respHeaders, is);
            } else {
                return new WebResourceResponse(mimeOnly, encoding, is);
            }
        } catch (SocketException se) {
            try { CrashLogger.w("Strict fetch socket error for " + urlStr + ": " + se, se); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { CrashLogger.w("Strict fetch failed for " + urlStr + ": " + t, t); } catch (Throwable ignored) {}
        } finally {
            // don't close connection input stream here; WebResourceResponse will use it
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

            boolean uaPresent = false;
            if (requestHeaders != null) {
                for (Map.Entry<String,String> e : requestHeaders.entrySet()) {
                    String k = e.getKey();
                    String v = e.getValue();
                    if (k == null || v == null) continue;
                    if ("host".equalsIgnoreCase(k) || "connection".equalsIgnoreCase(k)) continue;
                    httpsConn.setRequestProperty(k, v);
                    if ("user-agent".equalsIgnoreCase(k)) uaPresent = true;
                }
            }
            if (!uaPresent) httpsConn.setRequestProperty("User-Agent", DEFAULT_UA);
            httpsConn.setRequestProperty("Accept-Encoding", "identity");
            httpsConn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");

            httpsConn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            httpsConn.setReadTimeout(READ_TIMEOUT_MS);
            httpsConn.setInstanceFollowRedirects(true);
            int code2 = httpsConn.getResponseCode();
            InputStream is2 = (code2 >= 400) ? httpsConn.getErrorStream() : httpsConn.getInputStream();
            if (is2 == null) return null;
            String ct2 = httpsConn.getContentType();
            if (ct2 == null) ct2 = "application/octet-stream";

            String[] parts = splitMimeAndCharset(ct2);
            String mimeOnly = parts[0];
            String encoding = parts[1] != null ? parts[1] : chooseEncodingForMime(mimeOnly);

            Map<String,String> headers = new HashMap<>();
            for (Map.Entry<String, List<String>> hh : httpsConn.getHeaderFields().entrySet()) {
                String hk = hh.getKey();
                if (hk == null) continue;
                List<String> vals = hh.getValue();
                if (vals == null || vals.isEmpty()) continue;
                headers.put(hk, String.join(", ", vals));
            }
            if (!headers.containsKey("Access-Control-Allow-Origin")) headers.put("Access-Control-Allow-Origin", "*");

            try { CrashLogger.i("Combined-CA fetch returned for " + urlStr + " code=" + code2 + " type=" + ct2); } catch (Throwable ignored) {}
            if (Build.VERSION.SDK_INT >= 21) {
                return new WebResourceResponse(mimeOnly, encoding, code2, httpsConn.getResponseMessage(), headers, is2);
            } else {
                return new WebResourceResponse(mimeOnly, encoding, is2);
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
                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                    @Override
                    public void checkClientTrusted(X509Certificate[] certs, String authType) throws CertificateException {
                        // permissive: accept any client cert
                    }
                    @Override
                    public void checkServerTrusted(X509Certificate[] certs, String authType) throws CertificateException {
                        // permissive: accept any server cert
                    }
                }
        };
        sc.init(null, trustAllCerts, new SecureRandom());

        URL u = new URL(urlStr);
        HttpsURLConnection httpsConn = (HttpsURLConnection) u.openConnection();
        httpsConn.setSSLSocketFactory(sc.getSocketFactory());
        httpsConn.setHostnameVerifier((hostname, session) -> true);

        boolean uaPresent = false;
        if (requestHeaders != null) {
            for (Map.Entry<String,String> e : requestHeaders.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                if (k == null || v == null) continue;
                if ("host".equalsIgnoreCase(k) || "connection".equalsIgnoreCase(k)) continue;
                httpsConn.setRequestProperty(k, v);
                if ("user-agent".equalsIgnoreCase(k)) uaPresent = true;
            }
        }
        if (!uaPresent) httpsConn.setRequestProperty("User-Agent", DEFAULT_UA);
        httpsConn.setRequestProperty("Accept-Encoding", "identity");
        httpsConn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");

        httpsConn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        httpsConn.setReadTimeout(READ_TIMEOUT_MS);
        httpsConn.setInstanceFollowRedirects(true);
        int code2 = httpsConn.getResponseCode();
        InputStream is2 = (code2 >= 400) ? httpsConn.getErrorStream() : httpsConn.getInputStream();
        if (is2 == null) return null;
        String ct2 = httpsConn.getContentType();
        if (ct2 == null) ct2 = "application/octet-stream";

        String[] parts = splitMimeAndCharset(ct2);
        String mimeOnly = parts[0];
        String encoding = parts[1] != null ? parts[1] : chooseEncodingForMime(mimeOnly);

        Map<String,String> headers = new HashMap<>();
        for (Map.Entry<String, List<String>> hh : httpsConn.getHeaderFields().entrySet()) {
            String hk = hh.getKey();
            if (hk == null) continue;
            List<String> vals = hh.getValue();
            if (vals == null || vals.isEmpty()) continue;
            headers.put(hk, String.join(", ", vals));
        }
        if (!headers.containsKey("Access-Control-Allow-Origin")) headers.put("Access-Control-Allow-Origin", "*");

        try { CrashLogger.i("Permissive fetch returned for " + urlStr + " code=" + code2 + " type=" + ct2); } catch (Throwable ignored) {}
        if (Build.VERSION.SDK_INT >= 21) {
            return new WebResourceResponse(mimeOnly, encoding, code2, httpsConn.getResponseMessage(), headers, is2);
        } else {
            return new WebResourceResponse(mimeOnly, encoding, is2);
        }
    } catch (Throwable insecureEx) {
        try { CrashLogger.w("Permissive trust-all fetch failed for " + urlStr, insecureEx); } catch (Throwable ignored) {}
    }
    return null;
}
