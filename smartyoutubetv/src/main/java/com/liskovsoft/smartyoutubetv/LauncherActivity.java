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
 * LauncherActivity — debug-friendly variant
 *
 * - Starts a small local HTTP server that serves assets (NanoHTTPD)
 * - Intercepts network requests and prefers app-fetch (proxy) so we can log response headers/status
 * - Adds debug relaxations: accept SSL errors (optional), permissive CSP injected into local gjw.html,
 *   cookie enabling, clear caches on startup (for debugging), DOM snapshot on onPageFinished.
 *
 * This variant also injects visibility/debug probes:
 *  - HIDDEN_REPORT: list elements that are "hidden" or zero-size, with selectors/classes/text samples.
 *  - MUTATION: mutation observer logs attribute changes (class/style/hidden/aria-hidden/inert).
 *  - UNHIDE (optional): temporarily reveal hidden elements and outline them for manual inspection.
 *
 * NOTE: These relaxations are for debugging only. Remove or tighten for production.
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

    // Visibility debug helpers
    // ENABLE_VISIBILITY_DEBUG: when true, onPageFinished will inject HIDDEN_REPORT and MUTATION observer logs.
    // DEBUG_UNHIDE: if true, will try to temporarily unhide matching elements (INSECURE — only for local debugging).
    private static final boolean ENABLE_VISIBILITY_DEBUG = true;
    private static final boolean DEBUG_UNHIDE = false;

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
                    Log.e(TAG,"handleUrl failed",t); try{ CrashLogger.w("handleUrl failed", t);}catch(Throwable ignored){}
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

                // Visibility debug: hidden-elements report and mutation observer (optional unhide)
                if (ENABLE_VISIBILITY_DEBUG) {
                    try {
                        String hiddenProbe =
                            "(function(){ try{" +
                            "  function isHidden(el){" +
                            "    var cs = window.getComputedStyle(el); if(!cs) return false;" +
                            "    if(cs.display==='none') return 'display:none';" +
                            "    if(cs.visibility==='hidden' || cs.visibility==='collapse') return 'visibility';" +
                            "    if(parseFloat(cs.opacity)===0) return 'opacity';" +
                            "    var rect = el.getBoundingClientRect();" +
                            "    if(rect.width===0 || rect.height===0) return 'zero-size';" +
                            "    if(Math.abs(rect.right)<1 && Math.abs(rect.left)<1 && Math.abs(rect.top)<1 && Math.abs(rect.bottom)<1) return 'off-screen';" +
                            "    if(el.hasAttribute('hidden')) return 'hidden-attr';" +
                            "    if(el.getAttribute('aria-hidden')==='true') return 'aria-hidden';" +
                            "    if(el.hasAttribute('inert')) return 'inert';" +
                            "    return false;" +
                            "  }" +
                            "  function selectorFor(el){ try{ if(!el) return ''; if(el.id) return '#'+el.id; var s=el.tagName.toLowerCase(); if(el.classList && el.classList.length) s += '.'+Array.from(el.classList).slice(0,5).join('.'); return s; }catch(e){return '';} }" +
                            "  var nodes = Array.prototype.slice.call(document.querySelectorAll('body *'));" +
                            "  var report = [];" +
                            "  for(var i=0;i<nodes.length;i++){ try{ var el = nodes[i]; var why = isHidden(el); if(why){ var txt = (el.innerText||'').trim(); if(txt.length>200) txt = txt.substring(0,200)+'...'; report.push({sel: selectorFor(el), tag: el.tagName.toLowerCase(), classes: Array.from(el.classList).slice(0,6), why: why, textLen: (el.innerText||'').length, textSample: txt, aria: el.getAttribute('aria-hidden')||'', hiddenAttr: el.hasAttribute('hidden')}); } }catch(e){} }" +
                            "  if(window.Android && Android.log) Android.log('HIDDEN_REPORT:' + JSON.stringify(report));" +
                            "  return JSON.stringify({ok:true,found:report.length});" +
                            "}catch(e){ if(window.Android && Android.log) Android.log('HIDDEN_REPORT_ERR:'+e.toString()); return JSON.stringify({ok:false,err:String(e)}); }})();";
                        view.evaluateJavascript(hiddenProbe, new android.webkit.ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String value) {
                                try { CrashLogger.i("HIDDEN_REPORT eval result: " + value); } catch (Throwable ignored) {}
                            }
                        });
                    } catch (Throwable ignored) {}

                    try {
                        String mutationObserverProbe =
                            "(function(){ try{" +
                            "  var obs = new MutationObserver(function(muts){ try{ muts.forEach(function(m){ if(m.type==='attributes' && (m.attributeName==='class' || m.attributeName==='style' || m.attributeName==='hidden' || m.attributeName==='aria-hidden' || m.attributeName==='inert')){ var t=m.target; var info={ sel:(t.id?('#'+t.id):(t.tagName+(t.className?'.'+t.className:''))), attr:m.attributeName, val:t.getAttribute(m.attributeName), time:Date.now() }; if(window.Android && Android.log) Android.log('MUTATION:' + JSON.stringify(info)); } }); }catch(e){} });" +
                            "  obs.observe(document.body, { attributes:true, subtree:true, attributeFilter:['class','style','hidden','aria-hidden','inert'] });" +
                            "  if(window.Android && Android.log) Android.log('MUTATION_OBSERVER_STARTED');" +
                            "  return true;" +
                            "}catch(e){ if(window.Android && Android.log) Android.log('MUTATION_OBSERVER_ERR:'+e.toString()); return false; }})();";
                        view.evaluateJavascript(mutationObserverProbe, null);
                    } catch (Throwable ignored) {}

                    if (DEBUG_UNHIDE) {
                        try {
                            String unhideScript =
                                "(function(){ try{" +
                                "  var css = '*{ transition: none !important; } ._dbg_unhide{ outline:3px solid rgba(255,0,0,0.6) !important; }';" +
                                "  var s = document.createElement('style'); s.appendChild(document.createTextNode(css)); document.head && document.head.appendChild(s);" +
                                "  var els = Array.prototype.slice.call(document.querySelectorAll('body *'));" +
                                "  var cnt = 0;" +
                                "  for(var i=0;i<els.length;i++){ try{ var el = els[i]; var cs = window.getComputedStyle(el); if(!cs) continue; if(cs.display==='none' || cs.visibility==='hidden' || parseFloat(cs.opacity)===0 || el.hasAttribute('hidden') || el.getAttribute('aria-hidden')==='true' || el.hasAttribute('inert')){ el.classList.add('_dbg_unhide'); el.style.display='block'; el.style.visibility='visible'; el.style.opacity='1'; el.removeAttribute('hidden'); el.removeAttribute('inert'); el.setAttribute('data-dbg-unhidden','1'); cnt++; } }catch(e){} }" +
                                "  if(window.Android && Android.log) Android.log('UNHIDE_DONE:'+cnt);" +
                                "  return cnt;" +
                                "}catch(e){ if(window.Android && Android.log) Android.log('UNHIDE_ERR:'+e.toString()); return 0; }})();";
                            view.evaluateJavascript(unhideScript, null);
                        } catch (Throwable ignored) {}
                    }
                } // end ENABLE_VISIBILITY_DEBUG
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url){
                try { CrashLogger.i("shouldInterceptRequest: "+url); } catch (Throwable ignored) {}
                return tryServeAssetForUrl(url, Collections.emptyMap());
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

    // (Rest of file unchanged: fetchWithRetriesAndCache, helpers, LocalAssetsServer, etc.)
    // For brevity I omitted the unchanged downstream methods here; keep all existing helper methods in the real file.
    // The important injected changes are in onPageFinished above.
    //
    // Keep existing helper implementations (fetchWithRetriesAndCache, prepareCacheableResponseAndMaybeCache,
    // createCombinedTrustManagerFromAssets, LocalAssetsServer code, cache helpers, etc.) exactly as in your baseline.

    // helper: delete files/directories recursively
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
}
