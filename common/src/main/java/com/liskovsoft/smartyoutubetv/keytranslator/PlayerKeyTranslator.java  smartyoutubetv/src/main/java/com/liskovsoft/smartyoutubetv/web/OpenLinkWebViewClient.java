package com.liskovsoft.smartyoutubetv.web;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Lightweight WebViewClient that detects playable links and launches internal ExoPlayerActivity at runtime.
 * Includes runtime logging (CrashLogger) to verify the start attempt.
 *
 * Important: avoid compile-time dependency on browser module classes (start activity by class name).
 */
public class OpenLinkWebViewClient extends WebViewClient {
    private static final Pattern PLAYABLE_EXT = Pattern.compile("(?i).*\\.(mp4|m3u8|webm)$");
    private final Context mContext;
    private final boolean mPreferInternal;

    public OpenLinkWebViewClient(Context ctx, boolean preferInternal) {
        this.mContext = ctx.getApplicationContext();
        this.mPreferInternal = preferInternal;
    }

    public static void attachTo(android.webkit.WebView webView, Context ctx, boolean preferInternal) {
        webView.setWebViewClient(new OpenLinkWebViewClient(ctx, preferInternal));
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        String url = request != null && request.getUrl() != null ? request.getUrl().toString() : null;
        if (url == null) return false;

        if (isPlayableUrl(url)) {
            Map<String, String> headers = new HashMap<>();
            try {
                String ua = view.getSettings().getUserAgentString();
                if (ua != null) headers.put("User-Agent", ua);
            } catch (Throwable ignored) {}

            if (mPreferInternal) {
                startInternalPlayer(url, null, headers);
                return true;
            }
        }

        if (isYouTubeUrl(url)) {
            return false;
        }

        return false;
    }

    // Start activity by runtime class name to avoid compile-time dependency on browser module.
    private void startInternalPlayer(String url, String title, Map<String, String> headers) {
        try {
            Context ctx = mContext;
            Intent intent = new Intent();
            String appPkg = ctx.getPackageName();
            // FQCN of activity implemented in browser module; must match manifest merged into final app.
            String fqcn = "com.liskovsoft.browser.player.ExoPlayerActivity";

            intent.setClassName(appPkg, fqcn);
            intent.putExtra("extra_video_url", url);
            if (title != null) intent.putExtra("extra_title", title);
            if (headers != null && !headers.isEmpty()) {
                intent.putExtra("extra_headers", (Serializable) new HashMap<>(headers));
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // LOG before start
            String preMsg = "EXOPLAYER_START_ATTEMPT: pkg=" + appPkg + " class=" + fqcn + " url=" + url
                    + " headers_count=" + (headers == null ? 0 : headers.size());
            android.util.Log.i("OpenLinkWebViewClient", preMsg);
            try { com.szzdmj.nanohttpd.CrashLogger.i(preMsg); } catch (Throwable ignored) {}

            ctx.startActivity(intent);

            String okMsg = "EXOPLAYER_START_OK: started internal player for url=" + url;
            android.util.Log.i("OpenLinkWebViewClient", okMsg);
            try { com.szzdmj.nanohttpd.CrashLogger.i(okMsg); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            String errMsg = "EXOPLAYER_START_FAIL: will fallback to ACTION_VIEW for url=" + url + " err=" + t;
            android.util.Log.w("OpenLinkWebViewClient", errMsg, t);
            try { com.szzdmj.nanohttpd.CrashLogger.w(errMsg, null); } catch (Throwable ignored) {}

            // fallback to implicit
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(i);
                try { com.szzdmj.nanohttpd.CrashLogger.i("EXOPLAYER_START_FALLBACK_OK: ACTION_VIEW started for url=" + url); } catch (Throwable ignored) {}
            } catch (Throwable ignored2) {
                try { com.szzdmj.nanohttpd.CrashLogger.w("EXOPLAYER_START_FALLBACK_FAIL: " + ignored2, null); } catch (Throwable ignored3) {}
            }
        }
    }

    private boolean isPlayableUrl(String url) {
        if (url == null) return false;
        if (PLAYABLE_EXT.matcher(url).matches()) return true;
        if (url.toLowerCase().contains("m3u8")) return true;
        return false;
    }

    private boolean isYouTubeUrl(String url) {
        if (url == null) return false;
        try {
            android.net.Uri u = android.net.Uri.parse(url);
            String host = u.getHost();
            if (host == null) return false;
            host = host.toLowerCase();
            return host.contains("youtube.com") || host.contains("youtu.be");
        } catch (Throwable t) {
            return false;
        }
    }
}
