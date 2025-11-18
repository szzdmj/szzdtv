package com.liskovsoft.smartyoutubetv.web;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.liskovsoft.smartyoutubetv.player.PlayerActivity;
import com.szzdmj.nanohttpd.CrashLogger;

import java.io.ByteArrayInputStream;
import java.util.Locale;

/**
 * Lightweight WebViewClient that opens links.
 * If useInternalPlayer==true, video links (m3u8/mp4/webm etc.) and YouTube links will be
 * launched inside PlayerActivity (auto_fullscreen + auto_play). Otherwise ACTION_VIEW is used.
 *
 * Usage:
 *   OpenLinkWebViewClient.attachTo(webView, context, true);
 */
public class OpenLinkWebViewClient extends WebViewClient {
    private static final String TAG = "OpenLinkWebViewClient";
    private final Context mCtx;
    private final boolean mUseInternalPlayer;

    private OpenLinkWebViewClient(Context ctx, boolean useInternalPlayer) {
        this.mCtx = ctx.getApplicationContext();
        this.mUseInternalPlayer = useInternalPlayer;
    }

    public static void attachTo(WebView webView, Context ctx, boolean useInternalPlayer) {
        webView.setWebViewClient(new OpenLinkWebViewClient(ctx, useInternalPlayer));
    }

    private boolean isVideoOrHls(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.endsWith(".m3u8") || lower.endsWith(".mp4") || lower.endsWith(".webm")
                || lower.contains("youtube.com") || lower.contains("youtu.be");
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return handleUrl(url);
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        String url = request != null ? request.getUrl().toString() : null;
        return handleUrl(url);
    }

    private boolean handleUrl(String url) {
        try {
            if (url == null) return false;
            if (isVideoOrHls(url)) {
                if (mUseInternalPlayer) {
                    // launch internal player
                    try {
                        Intent i = PlayerActivity.createIntent(mCtx, Uri.parse(url));
                        i.putExtra("auto_fullscreen", true);
                        i.putExtra("auto_play", true);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mCtx.startActivity(i);
                        try { CrashLogger.i("OpenLinkWebViewClient: launched internal player for " + url); } catch (Throwable ignored) {}
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to launch internal player, fallback to ACTION_VIEW", t);
                        try { CrashLogger.w("Failed to launch internal player", t); } catch (Throwable ignored) {}
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mCtx.startActivity(intent);
                    }
                    return true; // we handled it
                } else {
                    // external viewer
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mCtx.startActivity(intent);
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "handleUrl failed", t);
            try { CrashLogger.w("OpenLinkWebViewClient.handleUrl failed", t); } catch (Throwable ignored) {}
        }
        return false;
    }

    // Optionally intercept resource requests to detect media requested via <video> or HLS fragments
    // and launch internal player proactively. Return null to let WebView continue loading.
    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        try {
            String url = request != null ? request.getUrl().toString() : null;
            if (isVideoOrHls(url) && mUseInternalPlayer) {
                // launch internal player and return empty response so WebView does not double-play
                try {
                    Intent i = PlayerActivity.createIntent(mCtx, Uri.parse(url));
                    i.putExtra("auto_fullscreen", true);
                    i.putExtra("auto_play", true);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mCtx.startActivity(i);
                    try { CrashLogger.i("OpenLinkWebViewClient: intercepted and launched internal player for " + url); } catch (Throwable ignored) {}
                } catch (Throwable t) {
                    try { CrashLogger.w("OpenLinkWebViewClient: failed to launch internal player for " + url, t); } catch (Throwable ignored) {}
                }
                // return 204 No Content (API>=21) or empty body to avoid WebView default handling
                if (Build.VERSION.SDK_INT >= 21) {
                    return new WebResourceResponse("text/plain", "UTF-8", 204, "No Content", null, new ByteArrayInputStream(new byte[0]));
                } else {
                    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                }
            }
        } catch (Throwable t) {
            try { CrashLogger.w("OpenLinkWebViewClient.shouldInterceptRequest failed", t); } catch (Throwable ignored) {}
        }
        return super.shouldInterceptRequest(view, request);
    }
}
