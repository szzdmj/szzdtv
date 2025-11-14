package com.liskovsoft.smartyoutubetv.web;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.util.Patterns;
import android.util.Log;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Minimal WebViewClient that intercepts clicked links.
 * - If the URL looks like a video (mp4/m3u8/webm/... or youtube link), it launches an ACTION_VIEW intent so an external player (or your player activity) will handle it.
 * - Otherwise, it lets the WebView load the URL normally.
 *
 * This is intentionally small and conservative: it avoids touching legacy modules and focuses on opening playback quickly.
 */
public class OpenLinkWebViewClient extends WebViewClient {
    private static final String TAG = "OpenLinkWebViewClient";
    private final Context mCtx;

    // basic video/file extension pattern (expand if needed)
    private static final Pattern VIDEO_EXT_PATTERN = Pattern.compile(".*\\.(mp4|m3u8|mkv|webm|mov|ts)(\\?.*)?$", Pattern.CASE_INSENSITIVE);

    public OpenLinkWebViewClient(Context ctx) {
        mCtx = ctx != null ? ctx.getApplicationContext() : null;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return handleUrl(view, url);
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        String url = request == null ? null : request.getUrl().toString();
        return handleUrl(view, url);
    }

    private boolean handleUrl(WebView view, String url) {
        if (url == null || mCtx == null) {
            return false; // let WebView handle it
        }

        String lower = url.toLowerCase(Locale.ROOT).trim();

        // If it's a simple http(s) URL but not likely a web page -> treat as video if extension or known host
        if (looksLikeVideoUrl(lower)) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setData(Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mCtx.startActivity(i);
                return true; // handled by external player
            } catch (ActivityNotFoundException e) {
                Log.w(TAG, "No external activity to handle video URL, falling back to WebView", e);
                // fallback to WebView loading
                return false;
            } catch (Exception e) {
                Log.e(TAG, "Error launching external player for url: " + url, e);
                return false;
            }
        }

        // For youtube short-links and watch links we also open externally (optional)
        if (isYouTubeLink(lower)) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mCtx.startActivity(i);
                return true;
            } catch (ActivityNotFoundException ex) {
                Log.w(TAG, "No handler for YouTube link, let WebView load it", ex);
                return false;
            }
        }

        // otherwise let the WebView load the page
        return false;
    }

    private boolean looksLikeVideoUrl(String url) {
        if (VIDEO_EXT_PATTERN.matcher(url).matches()) return true;
        // also allow direct links to common streaming patterns
        if (url.contains(".m3u8") || url.contains("/manifest") || url.contains("range=") || url.contains("videoplayback")) {
            return true;
        }
        return false;
    }

    private boolean isYouTubeLink(String url) {
        return url.contains("youtube.com/watch") || url.contains("youtu.be/");
    }

    /**
     * Convenience: attach to a WebView (sets client and enables JavaScript if desired).
     * Use this from your fragment/activity where you manage the WebView.
     */
    public static void attachTo(WebView webView, Context ctx, boolean enableJs) {
        if (enableJs) {
            webView.getSettings().setJavaScriptEnabled(true);
        }
        webView.setWebViewClient(new OpenLinkWebViewClient(ctx));
    }
}
