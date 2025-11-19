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
 * - Increased timeouts to support unstable networks (connect/read -> 3 minutes).
 * - Increased retry attempts for upstream fetch candidates.
 * - Force Accept-Encoding: identity when fetching upstream to avoid accidental gzip/chunk decode corruption.
 * - Auto-follow simple HTML redirects (meta-refresh / window.location) once for wrapped "blocking" HTML pages, to support the "404抢答/跳转还原" flow.
 * - Preserve previous protections: JS->HTML replacement still falls back to safe empty JS when HTML contains no redirect target.
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

    // Networking / retry tuning
    // Set to 3 minutes to tolerate unstable networks / throttling inside GFW
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

            private WebResourceResponse tryServeAssetForUrl(String url, Map<String, String> requestHeaders){
                try{
                    if (url == null) return null;
                    String lower = url.toLowerCase(Locale.ROOT);
                    String urlNoQuery = url.split("\\?")[0].split("#")[0];

// Replace the external-request branch inside tryServeAssetForUrl(...) with this upgraded handling.
// Key ideas:
// - If original request is http://..., try to fetch https://... first (using existing fetchWithRetriesAndCache).
// - If https fetch succeeds, return that response (log upgrade).
// - If https fails, fall back to existing behavior (attempt to fetch original http).
// - This forces https where available while preserving fallback.
if (lower.startsWith("http://") || lower.startsWith("https://")) {
    // If the original URL is http, attempt an immediate https upgrade and serve that if successful.
// Replace the external-request branch inside tryServeAssetForUrl(...) with this upgraded handling.
// Key: skip http->https upgrade and "block cleartext" logic for loopback/localhost.
if (lower.startsWith("http://") || lower.startsWith("https://")) {
    // If URL is loopback/local, DO NOT attempt https upgrade — local server may not support TLS.
    boolean isLocal = false;
    try {
        URL parsed = new URL(url);
        String host = parsed.getHost();
        if (host != null) {
            String h = host.toLowerCase(Locale.ROOT);
            if ("localhost".equals(h) || "127.0.0.1".equals(h) || "0.0.0.0".equals(h) || "::1".equals(h)) {
                isLocal = true;
            }
        }
    } catch (Throwable ignored) {}

    if (isLocal) {
        // Directly fetch local HTTP resource (don't upgrade, don't block cleartext).
        try {
            WebResourceResponse resp = fetchWithRetriesAndCache(url, requestHeaders);
            if (resp != null) {
                try { CrashLogger.i("Served local (no-upgrade) resource via app-fetch: " + url); } catch (Throwable ignored) {}
                return resp;
            } else {
                // If local fetch failed, let WebView do default (or return empty) — but do not attempt https upgrade.
                try { CrashLogger.w("Local fetch failed (no-upgrade) for: " + url, null); } catch (Throwable ignored) {}
                return null;
            }
        } catch (Throwable t) {
            try { CrashLogger.w("Exception while serving local resource: " + url + " : " + t, t); } catch (Throwable ignored) {}
            return null;
        }
    }
// inside tryServeAssetForUrl(...)
 // --- quick pass for local server: do NOT proxy/upgrade local requests ---
 if (lower.startsWith("http://localhost:") || lower.startsWith("https://localhost:") ||
     lower.startsWith("http://127.0.0.1:") || lower.startsWith("https://127.0.0.1:")) {
     // Let WebView talk to local NanoHTTPD directly (no https upgrade, no app proxy).
     try { CrashLogger.i("Bypassing proxy for local request: " + url); } catch (Throwable ignored) {}
     return null;
 }

 // External requests: prefer https upgrade for non-local http, otherwise normal fetch
 if (lower.startsWith("http://") || lower.startsWith("https://")) {
     // If original is http, try https upgrade first for non-local hosts
    if (lower.startsWith("http://")) {
        String httpsUrl = "https://" + url.substring("http://".length());
                            try {
            try { CrashLogger.i("Attempting http->https upgrade for: " + url + " -> " + httpsUrl); } catch (Throwable ignored) {}
            WebResourceResponse httpsResp = fetchWithRetriesAndCache(httpsUrl, requestHeaders);
            if (httpsResp != null) {
                try { CrashLogger.i("Upgraded http->https for " + url + " -> " + httpsUrl); } catch (Throwable ignored) {}
                // Return the https response directly. Caller will log Served remote resource via app-fetch with original url.
                return httpsResp;
                        } else {
                try { CrashLogger.i("http->https upgrade failed or no https content for: " + url); } catch (Throwable ignored) {}
                        }
        } catch (Throwable t) {
            try { CrashLogger.w("http->https upgrade attempt failed for " + url + ": " + t, t); } catch (Throwable ignored) {}
             // fallthrough to fetch original
                    }
                    }

    // Normal fetch flow (for https original or upgrade-failed)
                        WebResourceResponse resp = fetchWithRetriesAndCache(url, requestHeaders);
                        if (resp != null) {
                            try { CrashLogger.i("Served remote resource via app-fetch: " + url); } catch (Throwable ignored) {}
                            return resp;
                        } else {
        // Only block cleartext if it's not local. (For local we've already returned above.)
                            if (lower.startsWith("http://")) {
            try { CrashLogger.i("Blocking cleartext request for " + url + " (no available content)"); } catch (Throwable ignored) {}
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

            // If JS requested but server returned HTML (404/520 pages), return safe empty JS to avoid parse error.
            try {
                Charset cs = (encoding != null) ? Charset.forName(encoding) : StandardCharsets.UTF_8;
                if (isJsRequest(origUrl) && (mimeOnly.contains("html") || looksLikeHtml(data, cs))) {
                    String note = "/* blocked returned HTML for JS request: replaced with empty JS to avoid parse error */";
                    byte[] empty = note.getBytes(StandardCharsets.UTF_8);
                    data = empty;
                    mimeOnly = "application/javascript";
                    encoding = "UTF-8";
                    try { CrashLogger.i("Replaced unexpected HTML response with empty JS for: " + origUrl); } catch (Throwable ignored) {}
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
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setInstanceFollowRedirects(true);

                boolean uaPresent = false;
                if (incomingRequestHeaders != null) {
                    for (Map.Entry<String, String> e : incomingRequestHeaders.entrySet()) {
                        String k = e.getKey();
                        String v = e.getValue();
                        if (k == null || v == null) continue;
                        // Do not set Host/Connection which are managed by URLConnection
                        if ("host".equalsIgnoreCase(k) || "connection".equalsIgnoreCase(k)) continue;
                        conn.setRequestProperty(k, v);
                        if ("user-agent".equalsIgnoreCase(k)) uaPresent = true;
                    }
                }
                if (!uaPresent) conn.setRequestProperty("User-Agent", DEFAULT_UA);
                conn.setRequestProperty("Accept-Encoding", "identity");
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");

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
                    httpsConn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    httpsConn.setReadTimeout(READ_TIMEOUT_MS);
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
                    httpsConn.setRequestProperty("Accept-Encoding", "identity");
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
                    httpsConn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    httpsConn.setReadTimeout(READ_TIMEOUT_MS);
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
                    httpsConn.setRequestProperty("Accept-Encoding", "identity");
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

    private int findFreePort() throws IOException {
        java.net.InetAddress loopback = java.net.InetAddress.getByName("127.0.0.1");
        try (ServerSocket socket = new ServerSocket(0, 0, loopback)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
