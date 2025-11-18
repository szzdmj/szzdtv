// 仅展示修改/新增部分，建议直接替换整个文件为此版本（基于你现有实现增加播放器拦截与去抖）
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
import android.webkit.MimeTypeMap;
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class LauncherActivity extends AppCompatActivity {
    private static final String TAG = "LauncherActivity";
    private WebView webView;
    private LocalAssetsServer server;
    private int serverPort = -1;

    // 去抖：上次启动的 URL 与时间（避免短时间内重复打开播放器）
    private volatile String lastLaunchedUrl = null;
    private volatile long lastLaunchTs = 0;
    private static final long LAUNCH_DEBOUNCE_MS = 1500;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // ... 你原来的初始化不变（省略） ...
    }

    // 在 WebViewClient.tryServeAssetForUrl 内检测并触发播放器的辅助方法：
    private void tryLaunchPlayerIfMedia(final String url) {
        if (url == null) return;
        // 基础匹配（文件后缀/扩展名），你可以根据需要扩展正则
        String lower = url.toLowerCase(Locale.ROOT);
        boolean looksLikeMedia = lower.endsWith(".m3u8") || lower.endsWith(".mp4") || lower.endsWith(".webm") ||
                                 lower.endsWith(".m4a") || lower.endsWith(".aac");
        if (!looksLikeMedia) return;

        final long now = System.currentTimeMillis();
        if (url.equals(lastLaunchedUrl) && (now - lastLaunchTs) < LAUNCH_DEBOUNCE_MS) {
            // 已经在短时间内启动过同一 URL，忽略
            return;
        }
        lastLaunchedUrl = url;
        lastLaunchTs = now;

        // 启动 PlayerActivity 必须在 UI 线程
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    CrashLogger.i("Launching player for " + url);
                } catch (Throwable ignored) {}
                try {
                    Intent i = PlayerActivity.createIntent(LauncherActivity.this, Uri.parse(url));
                    // 约定 extra：自动全屏并立即播放
                    i.putExtra("auto_fullscreen", true);
                    i.putExtra("auto_play", true);
                    startActivity(i);
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to launch PlayerActivity", t);
                    try { CrashLogger.w("Failed to launch PlayerActivity", t); } catch (Throwable ignored) {}
                }
            }
        });
    }

    // tryServeAssetForUrl 的关键变更：在发现媒体资源时调用 tryLaunchPlayerIfMedia() 并返回空响应给 WebView
    private WebResourceResponse tryServeAssetForUrl(String url){
        try{
            if (url == null) return null;
            String lower = url.toLowerCase(Locale.ROOT);

            // 1) 本地 assets / js 拦截（保持你已实现的逻辑）
            //    ... existing logic ...

            // 2) 如果是本机 localhost，不做处理
            if (lower.startsWith("http://localhost:") || lower.startsWith("http://127.0.0.1:") ||
                lower.startsWith("https://localhost:") || lower.startsWith("https://127.0.0.1:")) {
                return null;
            }

            // 3) 如果是媒体资源（m3u8/mp4 等），启动内置播放器并返回空响应给 WebView，阻止 WebView 自己处理
            if (lower.endsWith(".m3u8") || lower.endsWith(".mp4") || lower.endsWith(".webm") ||
                lower.endsWith(".m4a") || lower.endsWith(".aac")) {

                // 记录并拉起播放器
                tryLaunchPlayerIfMedia(url);

                // 返回空响应给 WebView。API 21+ 可设置状态码 204 No Content
                if (Build.VERSION.SDK_INT >= 21) {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Content-Type", "text/plain");
                    return new WebResourceResponse("text/plain", "UTF-8", 204, "No Content", headers, new ByteArrayInputStream(new byte[0]));
                } else {
                    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                }
            }

            // 4) 对外部 http 请求尝试 https 回退（保持你已有实现）
            if (lower.startsWith("http://")) {
                WebResourceResponse httpsResp = tryFetchHttpsFallback(url);
                if (httpsResp != null) {
                    try { CrashLogger.i("HTTPS fallback succeeded for " + url); } catch (Throwable ignored) {}
                    return httpsResp;
                } else {
                    try { CrashLogger.i("HTTPS fallback failed for " + url); } catch (Throwable ignored) {}
                }
            }

        }catch(Throwable t){
            Log.w(TAG,"tryServeAssetForUrl failed for "+url,t);
            try{ CrashLogger.w("tryServeAssetForUrl failed for "+url,t);}catch(Throwable ignored){}
        }
        return null;
    }

    // 你原来的 tryFetchHttpsFallback/LocalAssetsServer 等保持（省略）...
    // 确保 tryFetchHttpsFallback 放在类中并且 LocalAssetsServer 保持原样或使用你当前版本。
}
