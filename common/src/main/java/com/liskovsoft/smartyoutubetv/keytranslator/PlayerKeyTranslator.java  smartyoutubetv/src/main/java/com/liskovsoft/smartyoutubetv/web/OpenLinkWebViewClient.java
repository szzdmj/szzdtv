package com.liskovsoft.smartyoutubetv.web;

import android.content.Context;
import android.net.Uri;
import android.util.Patterns;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.liskovsoft.browser.player.ExoPlayerActivity;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Lightweight WebViewClient that detects playable links and launches internal ExoPlayerActivity.
 *
 * Keep this class simple: no top-level statements allowed. All logic must be inside methods.
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

        // If URL looks like a media resource, handle it.
        if (isPlayableUrl(url)) {
            // Collect optional headers if you need them (for now we only include User-Agent)
            Map<String, String> headers = new HashMap<>();
            try {
                String ua = view.getSettings().getUserAgentString();
                if (ua != null) headers.put("User-Agent", ua);
            } catch (Throwable ignored) {}

            // Prefer internal immersive player if configured; otherwise let system handle (fallback)
            if (mPreferInternal) {
                ExoPlayerActivity.start(mContext, url, null, headers);
                return true; // we handled the navigation
            }
        }

        // For youtube links or other special handling you can extend here
        if (isYouTubeUrl(url)) {
            // Optionally prefer system or internal handling; default: allow WebView (or you can launch external)
            return false;
        }

        return false; // default: let WebView handle
    }

    // Helper: quick playable URL heuristic
    private boolean isPlayableUrl(String url) {
        if (url == null) return false;
        if (PLAYABLE_EXT.matcher(url).matches()) return true;
        // also accept streaming playlist patterns
        if (url.toLowerCase().contains("m3u8")) return true;
        return false;
    }

    private boolean isYouTubeUrl(String url) {
        if (url == null) return false;
        Uri u = Uri.parse(url);
        String host = u.getHost();
        if (host == null) return false;
        host = host.toLowerCase();
        return host.contains("youtube.com") || host.contains("youtu.be");
    }
}
