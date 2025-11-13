package com.liskovsoft.browser.xwalk;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * WebView-based replacement for Crosswalk XWalkBrowserFragment.
 *
 * Notes:
 * - This Fragment intentionally does not try to play media inside WebView.
 *   Instead it notifies the host via BrowserPlayerCallback.playUrl(url)
 *   when it detects a media URL (eg. .mp4, .m3u8, .ts). Host is expected
 *   to handle playback using ExoPlayer (or other native player).
 *
 * - This single-file replacement is intended as an incremental step:
 *   add ExoPlayer-based playback in the host Activity in the next step.
 */
public class XWalkBrowserFragment extends Fragment {
    private WebView mWebView;
    private BrowserPlayerCallback mPlayerCallback;
    private static final String ARG_URL = "arg_url";

    public interface BrowserPlayerCallback {
        /**
         * Host should handle playback of the supplied media URL (ExoPlayer recommended).
         */
        void playUrl(String url);
    }

    public static XWalkBrowserFragment newInstance(String url) {
        XWalkBrowserFragment f = new XWalkBrowserFragment();
        Bundle b = new Bundle();
        b.putString(ARG_URL, url);
        f.setArguments(b);
        return f;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (context instanceof BrowserPlayerCallback) {
            mPlayerCallback = (BrowserPlayerCallback) context;
        } else {
            mPlayerCallback = null; // host may set later via setter
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mPlayerCallback = null;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // create WebView programmatically to avoid layout dependencies for now
        mWebView = new WebView(requireContext());
        configureWebView(mWebView);

        // load initial URL if provided
        Bundle args = getArguments();
        if (args != null) {
            String url = args.getString(ARG_URL);
            if (url != null) loadUrl(url);
        }
        return mWebView;
    }

    private void configureWebView(WebView webView) {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setLoadsImagesAutomatically(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        // enable media playback without gesture where possible (for TV-friendly behavior)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            ws.setMediaPlaybackRequiresUserGesture(false);
        }

        // Cookie sync for cookies in WebView
        CookieManager.getInstance().setAcceptCookie(true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new BrowserWebViewClient());
    }

    /**
     * Basic detection for media URLs. This is intentionally conservative; update patterns as needed.
     */
    private boolean isMediaUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        if (u.endsWith(".mp4") || u.contains(".m3u8") || u.endsWith(".ts") || u.endsWith(".webm")) return true;
        // common streaming query patterns
        if (u.contains("format=") && (u.contains("mp4") || u.contains("m3u8"))) return true;
        // add more heuristic checks here if needed
        return false;
    }

    /**
     * Public API to load a URL into the WebView.
     */
    public void loadUrl(String url) {
        if (mWebView == null) return;
        mWebView.loadUrl(url);
    }

    /**
     * Public getter for the WebView in case host wants to tweak settings or add JS interfaces.
     */
    public WebView getWebView() {
        return mWebView;
    }

    /**
     * Allow host to set player callback if it couldn't during attach.
     */
    public void setBrowserPlayerCallback(BrowserPlayerCallback cb) {
        this.mPlayerCallback = cb;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mWebView != null) mWebView.onResume();
    }

    @Override
    public void onPause() {
        if (mWebView != null) mWebView.onPause();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        // Properly destroy WebView to avoid leaks
        if (mWebView != null) {
            mWebView.loadUrl("about:blank");
            mWebView.stopLoading();
            mWebView.setWebChromeClient(null);
            mWebView.setWebViewClient(null);
            mWebView.removeAllViews();
            mWebView.destroy();
            mWebView = null;
        }
        super.onDestroyView();
    }

    private class BrowserWebViewClient extends WebViewClient {
        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (isMediaUrl(url)) {
                if (mPlayerCallback != null) {
                    mPlayerCallback.playUrl(url);
                }
                return true; // handled
            }
            // allow WebView to load the URL
            return false;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = (request != null ? request.getUrl() : null);
            String url = uri != null ? uri.toString() : null;
            if (isMediaUrl(url)) {
                if (mPlayerCallback != null) {
                    mPlayerCallback.playUrl(url);
                }
                return true;
            }
            return false;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            // can add loading UI callbacks if host supports it
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            // post-load actions if needed
        }
    }
}
