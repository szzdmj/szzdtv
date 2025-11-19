// Insert these inside the LauncherActivity class (place the field near other fields, and put the method among other instance methods).
// Do NOT paste these outside the class braces.

    // --- debug request counting map (place with other fields) ---
    private final java.util.Map<String, Integer> requestCounts = Collections.synchronizedMap(new java.util.HashMap<>());

    // --- replacement tryServeAssetForUrl method (put inside the class) ---
    private WebResourceResponse tryServeAssetForUrl(String url, Map<String, String> requestHeaders){
        try {
            if (url == null) return null;
            String lower = url.toLowerCase(Locale.ROOT);

            // Count requests for this URL (simple rate-limiting of logs)
            int count = 1;
            try {
                Integer prev = requestCounts.get(lower);
                count = (prev == null) ? 1 : prev + 1;
                requestCounts.put(lower, count);
            } catch (Throwable ignored) {}

            // Decision log (limited by count)
            String info = String.format(Locale.US, "Intercept decision: url=%s count=%d", url, count);
            Log.i(TAG, info);
            try { CrashLogger.i(info); } catch (Throwable ignored) {}

            // 1) Bypass local server requests (let NanoHTTPD/LocalAssetsServer handle these)
            if (lower.startsWith("http://127.0.0.1:") || lower.startsWith("http://localhost:")) {
                String msg = "Bypass local request -> letting LocalAssetsServer handle: " + url;
                Log.d(TAG, msg);
                try { CrashLogger.i(msg); } catch (Throwable ignored) {}
                return null;
            }

            // 2) If this is a request for a packaged JS file, try to serve from assets and log that action
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
                        // asset not present in APK: record and allow WebView to load from network
                        String msg = "Asset not found in APK for " + name + "; allowing WebView to fetch: " + url;
                        Log.i(TAG, msg);
                        try { CrashLogger.i(msg); } catch (Throwable ignored) {}
                        // fall through to network branch -> return null
                    }
                }
            }

            // 3) For all external http(s) requests: do not proxy; record brief header summary and return null
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                String msg = "Allowing WebView to fetch network resource directly: " + url;
                Log.i(TAG, msg);
                try { CrashLogger.i(msg); } catch (Throwable ignored) {}

                // Request header summary logging (avoid printing huge headers)
                try {
                    if (requestHeaders != null && !requestHeaders.isEmpty()) {
                        String hdrSummary = "headers_count=" + requestHeaders.size();
                        try { CrashLogger.i("Request headers summary for " + url + " -> " + hdrSummary); } catch (Throwable ignored) {}
                        // If you want full headers, uncomment next line (may be verbose):
                        // try { CrashLogger.i("RequestHeaders: " + requestHeaders.toString()); } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}

                // Suppress repeated logs for very high-frequency URLs (simple threshold)
                try {
                    if (count > 20) {
                        if (count == 21) {
                            try { CrashLogger.i("High-frequency request: suppressing further per-request logs for " + url); } catch (Throwable ignored) {}
                        }
                        return null;
                    }
                } catch (Throwable ignored) {}

                return null;
            }

        } catch (Throwable t) {
            Log.w(TAG, "tryServeAssetForUrl failed for " + url, t);
            try { CrashLogger.w("tryServeAssetForUrl failed for " + url, t); } catch (Throwable ignored) {}
        }
        return null;
    }
