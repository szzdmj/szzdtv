// 1) 把这个字段加入类的字段区（与其他 fields 同级）
private final java.util.Map<String, Integer> requestCounts = Collections.synchronizedMap(new java.util.HashMap<>());

// 2) 用下面的方法替换现有的 tryServeAssetForUrl(...) 实现
private WebResourceResponse tryServeAssetForUrl(String url, Map<String, String> requestHeaders){
    try {
        if (url == null) return null;
        String lower = url.toLowerCase(Locale.ROOT);

        // 计数并限流日志（避免在短时间内刷屏）
        int count = 1;
        try {
            Integer prev = requestCounts.get(lower);
            count = (prev == null) ? 1 : prev + 1;
            requestCounts.put(lower, count);
        } catch (Throwable ignored) {}

        // 详细日志：到达与次数
        String info = String.format(Locale.US, "Intercept decision: url=%s count=%d", url, count);
        Log.i(TAG, info);
        try { CrashLogger.i(info); } catch (Throwable ignored) {}

        // 1) 本地 server 请求绕过（仍让本地 NanoHTTPD 处理）
        if (lower.startsWith("http://127.0.0.1:") || lower.startsWith("http://localhost:")) {
            String msg = "Bypass local request -> letting LocalAssetsServer handle: " + url;
            Log.d(TAG, msg);
            try { CrashLogger.i(msg); } catch (Throwable ignored) {}
            return null;
        }

        // 2) 如果是请求本地打包的 JS，优先从 assets 返回（并记录）
        if (lower.endsWith(".js")) {
            String name = url.substring(url.lastIndexOf('/') + 1).split("\\?")[0];
            try {
                InputStream is = getAssets().open(name);
                String msg = "Serving JS from assets: " + name + " for url=" + url;
                Log.i(TAG, msg);
                try { CrashLogger.i(msg); } catch (Throwable ignored) {}
                return new WebResourceResponse("application/javascript", "UTF-8", is);
            } catch (IOException ignored) {
                try {
                    InputStream is = getAssets().open("js/" + name);
                    String msg = "Serving JS from assets/js/: " + name + " for url=" + url;
                    Log.i(TAG, msg);
                    try { CrashLogger.i(msg); } catch (Throwable ignored) {}
                    return new WebResourceResponse("application/javascript", "UTF-8", is);
                } catch (IOException ex) {
                    // asset 不存在——记录并允许 WebView 继续从网络获取
                    String msg = "Asset not found in APK for " + name + "; allowing WebView to fetch: " + url;
                    Log.i(TAG, msg);
                    try { CrashLogger.i(msg); } catch (Throwable ignored) {}
                    // 继续到下面的网络分支 -> return null
                }
            }
        }

        // 3) 对所有外部 http(s) 请求：不再代理，记录更多信息并返回 null（由 WebView 发起真实网络请求）
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            String msg = "Allowing WebView to fetch network resource directly: " + url;
            Log.i(TAG, msg);
            try { CrashLogger.i(msg); } catch (Throwable ignored) {}

            // 记录请求头的简要信息（若 headers 太大，只打印 key 列表 / size）
            try {
                if (requestHeaders != null && !requestHeaders.isEmpty()) {
                    String hdrSummary = "headers_count=" + requestHeaders.size();
                    try { CrashLogger.i("Request headers summary for " + url + " -> " + hdrSummary); } catch (Throwable ignored) {}
                    // 若需要，可打印全部 headers： CrashLogger.i(requestHeaders.toString());
                }
            } catch (Throwable ignored) {}

            // 限制日志频率：如果同一个 URL 超过阈值（例如 20 次），就减少后续日志量以免刷屏
            try {
                if (count > 20) {
                    if (count == 21) {
                        try { CrashLogger.i("High-frequency request: suppressing further per-request logs for " + url); } catch (Throwable ignored) {}
                    }
                    // 仍然返回 null（WebView 继续）
                    return null;
                }
            } catch (Throwable ignored) {}

            return null;
        }

    } catch (Throwable t) {
        Log.w(TAG, "tryServeAssetForUrl failed for " + url, t);
        try { CrashLogger.w("tryServeAssetForUrl failed for "+url, t); } catch (Throwable ignored) {}
    }
    return null;
}
