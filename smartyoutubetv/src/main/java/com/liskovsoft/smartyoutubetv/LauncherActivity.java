// 替换文件中的 tryFetchHttpsFallback(...) 实现为下面内容

/**
 * Try fetching https:// version of a given http:// URL.
 * Two modes:
 *  - secure mode: use default TLS (system trust); only succeeds if cert chain valid
 *  - insecure permissive mode: create SSLContext that trusts all certs + permissive HostnameVerifier
 *
 * Controlled by:
 *   boolean INSECURE_HTTPS_FALLBACK = true; // 开发/临时使用时打开
 *   Set<String> HTTPS_WHITELIST_SUFFIXES = ...  // 仅对这些后缀允许 insecure fallback (建议)
 */
private static final boolean INSECURE_HTTPS_FALLBACK = true; // <- 临时调试时可设 true，发布请设 false
private static final String[] HTTPS_WHITELIST_SUFFIXES = new String[] {
    // 可按需调整或留空以允许所有域（风险高）。建议列出可信的域后缀，例如：
    "cloudfront.net",
    "s3.amazonaws.com",
    "amazonaws.com"
    // 如果希望对任意域都强制尝试 insecure fallback，可以把数组留空并设置 INSECURE_HTTPS_FALLBACK = true（危险）
};

private boolean hostMatchesWhitelist(String host) {
    if (host == null) return false;
    if (HTTPS_WHITELIST_SUFFIXES == null || HTTPS_WHITELIST_SUFFIXES.length == 0) {
        // 当数组为空时，视为不限制（谨慎使用）
        return INSECURE_HTTPS_FALLBACK;
    }
    for (String suf : HTTPS_WHITELIST_SUFFIXES) {
        if (host.endsWith(suf)) return true;
    }
    return false;
}

private WebResourceResponse tryFetchHttpsFallback(String url) {
    if (url == null || !url.startsWith("http://")) return null;
    String httpsUrl = "https://" + url.substring(7);
    java.net.HttpURLConnection conn = null;
    try {
        java.net.URL u = new java.net.URL(httpsUrl);
        String host = u.getHost();

        boolean tryInsecure = INSECURE_HTTPS_FALLBACK && hostMatchesWhitelist(host);

        if (!tryInsecure) {
            // 正常安全方式
            conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                String contentType = conn.getContentType();
                if (contentType == null) contentType = "application/octet-stream";
                InputStream is = conn.getInputStream();
                return new WebResourceResponse(contentType, "UTF-8", is);
            } else {
                try { CrashLogger.i("HTTPS fallback returned non-2xx for " + httpsUrl + " code=" + code); } catch (Throwable ignored) {}
            }
            return null;
        }

        // —— insecure mode: create permissive SSLContext & HostnameVerifier —— //
        javax.net.ssl.HttpsURLConnection httpsConn = (javax.net.ssl.HttpsURLConnection) u.openConnection();
        httpsConn.setConnectTimeout(4000);
        httpsConn.setReadTimeout(6000);
        httpsConn.setInstanceFollowRedirects(true);

        // Create an SSLContext that trusts all certificates (INSECURE)
        javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
        javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                }
        };
        sc.init(null, trustAllCerts, new java.security.SecureRandom());
        javax.net.ssl.SSLSocketFactory sslSocketFactory = sc.getSocketFactory();
        httpsConn.setSSLSocketFactory(sslSocketFactory);

        // permissive hostname verifier (INSECURE)
        httpsConn.setHostnameVerifier(new javax.net.ssl.HostnameVerifier() {
            @Override
            public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
                return true;
            }
        });

        int code = httpsConn.getResponseCode();
        if (code >= 200 && code < 300) {
            String contentType = httpsConn.getContentType();
            if (contentType == null) contentType = "application/octet-stream";
            InputStream is = httpsConn.getInputStream();
            try { CrashLogger.i("HTTPS insecure fallback success for " + httpsUrl); } catch (Throwable ignored) {}
            // Note: do NOT disconnect here — WebView will read the stream
            return new WebResourceResponse(contentType, "UTF-8", is);
        } else {
            try { CrashLogger.i("HTTPS fallback returned non-2xx for " + httpsUrl + " code=" + code); } catch (Throwable ignored) {}
        }
    } catch (Throwable t) {
        try { CrashLogger.w("HTTPS fallback failed for " + httpsUrl, t); } catch (Throwable ignored) {}
    } finally {
        // if conn was plain HttpURLConnection and not used (or ended), ensure disconnect
        if (conn != null) conn.disconnect();
    }
    return null;
}
