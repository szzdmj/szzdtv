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
import android.os.Bundle;
import java.util.HashMap;
import java.util.Map;

import java.io.ByteArrayInputStream;
import java.util.Locale;

/**
 * Lightweight WebViewClient that opens links.
 * - Does NOT compile-time depend on PlayerActivity or CrashLogger (avoids common-module compile errors).
 * - If useInternalPlayer==true, tries to start internal PlayerActivity by class name at runtime;
 *   if that fails falls back to ACTION_VIEW.
 *
 * Usage:
 *   OpenLinkWebViewClient.attachTo(webView, context, true);
 */
// 假设现有代码在某处检测到可播放链接 `String mediaUrl`
Context ctx = webView.getContext();
String mediaUrl = detectedUrl; // 原来逻辑得到的播放 URL
String title = null; // 如果能从页面或元素获得标题则设置
// OPTIONAL: 从 WebResourceRequest 或页面上下文提取有用的 headers（若需要）
Map<String, String> headers = new HashMap<>();
// 例如：headers.put("User-Agent", webView.getSettings().getUserAgentString());
// 如果不需要 headers，可传 null

// 启动沉浸式播放
com.liskovsoft.browser.player.ExoPlayerActivity.start(ctx, mediaUrl, title, headers);

// 如果你仍想保留原来的外部 Intent 备份逻辑，可在 start 前后做 try/catch 或按条件回退。
// end of snippet
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
                    // Try to launch internal PlayerActivity by class name at runtime.
                    try {
                        Intent i = new Intent();
                        i.setAction(Intent.ACTION_VIEW);
                        i.setData(Uri.parse(url));
                        // setClassName to avoid compile-time dependency on PlayerActivity
                        i.setClassName(mCtx.getPackageName(), "com.liskovsoft.smartyoutubetv.player.PlayerActivity");
                        i.putExtra("auto_fullscreen", true);
                        i.putExtra("auto_play", true);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mCtx.startActivity(i);
                        Log.i(TAG, "launched internal player for " + url);
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to launch internal player, fallback to ACTION_VIEW", t);
                        // fallback: external viewer
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            mCtx.startActivity(intent);
                        } catch (Throwable t2) {
                            Log.w(TAG, "Fall-back ACTION_VIEW failed", t2);
                        }
                    }
                    return true; // handled
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
        }
        return false;
    }

    @Override
    public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        try {
            String url = request != null ? request.getUrl().toString() : null;
            if (isVideoOrHls(url) && mUseInternalPlayer) {
                // Launch internal player (same runtime approach) and return empty response
                try {
                    Intent i = new Intent();
                    i.setAction(Intent.ACTION_VIEW);
                    i.setData(Uri.parse(url));
                    i.setClassName(mCtx.getPackageName(), "com.liskovsoft.smartyoutubetv.player.PlayerActivity");
                    i.putExtra("auto_fullscreen", true);
                    i.putExtra("auto_play", true);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mCtx.startActivity(i);
                    Log.i(TAG, "intercepted and launched internal player for " + url);
                } catch (Throwable t) {
                    Log.w(TAG, "failed to launch internal player for " + url, t);
                }
                if (Build.VERSION.SDK_INT >= 21) {
                    return new WebResourceResponse("text/plain", "UTF-8", 204, "No Content", null, new ByteArrayInputStream(new byte[0]));
                } else {
                    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "shouldInterceptRequest failed", t);
        }
        return super.shouldInterceptRequest(view, request);
    }
}
