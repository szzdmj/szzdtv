package com.liskovsoft.smartyoutubetv;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.liskovsoft.smartyoutubetv.player.PlayerActivity;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * LauncherActivity with improved WebView debugging and asset-based fallback for JS files.
 *
 * - Enables file access & universal access from file URLs
 * - Logs JS console messages to Android log
 * - Intercepts requests for common JS files and serves them from assets if present
 * - For playable links, forwards to PlayerActivity
 */
public class LauncherActivity extends AppCompatActivity {
    private static final String TAG = "LauncherActivity";
    private WebView webView;

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

        // Load the index page from assets
        webView.loadUrl("file:///android_asset/gjw.html");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
    }
}
