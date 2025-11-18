package com.liskovsoft.smartyoutubetv;

import android.content.res.AssetManager;
import android.util.Log;

import com.szzdmj.nanohttpd.CrashLogger;

import fi.iki.elonen.NanoHTTPD;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * LocalAssetsServer: serves files from assets with clear logs and optional shim injection.
 * This variant mirrors the example LocalHttpServer: injects id-shim for index.html and logs via CrashLogger.
 */
public class LocalAssetsServer extends NanoHTTPD {
    private static final String TAG = "LocalAssetsServer";
    private final AssetManager assets;

    public LocalAssetsServer(int port, AssetManager assets) throws IOException {
    super("127.0.0.1", port); // bind explicitly to loopback
        this.assets = assets;
        Log.d(TAG, "Constructed LocalAssetsServer for port " + port);
        try { CrashLogger.i("Constructed LocalAssetsServer for port " + port); } catch (Throwable ignored) {}
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        String remote = session.getHeaders() != null ? session.getHeaders().get("remote-addr") : null;
        Log.d(TAG, "Incoming request: uri=" + uri + ", remote=" + remote + ", method=" + session.getMethod());
        try { CrashLogger.i("HTTP request: " + session.getMethod() + " " + uri + " remote=" + remote); } catch (Throwable ignored) {}

        if (uri == null || uri.isEmpty() || uri.equals("/")) uri = "/gjw.html";
        String path = uri.startsWith("/") ? uri.substring(1) : uri;
        if (path.contains("..")) {
            try { CrashLogger.w("Forbidden path traversal: " + path, null); } catch (Throwable ignored) {}
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden");
        }

        try {
            // special shim path
            if ("/__shim__/id-shim.js".equals("/" + path)) {
                InputStream in = assets.open("id-shim.js");
                CrashLogger.i("Serving id-shim.js");
                return newChunkedResponse(Response.Status.OK, "application/javascript", in);
            }

            InputStream is = assets.open(path);
            if (path.endsWith("index.html")) {
                String html = readAll(is, "UTF-8");
                html = html.replace(
                        "<script type=\"text/javascript\" src=\"webjs.js\"></script>",
                        "<script type=\"text/javascript\" src=\"/__shim__/id-shim.js\"></script>\n" +
                                "<script type=\"text/javascript\" src=\"webjs.js\"></script>"
                );
                CrashLogger.i("Serving modified index.html (shim injected)");
                Response r = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html);
                r.addHeader("Access-Control-Allow-Origin", "*");
                r.addHeader("Cache-Control", "no-cache");
                return r;
            }

            String mime = guessMime(path);
            CrashLogger.i("Serving asset: " + path + " as " + mime);
            Response res = newChunkedResponse(Response.Status.OK, mime, is);
            res.addHeader("Access-Control-Allow-Origin", "*");
            res.addHeader("Cache-Control", "no-cache");
            return res;
        } catch (IOException e) {
            Log.w(TAG, "Asset not found: " + path);
            try { CrashLogger.w("Asset not found: " + path, e); } catch (Throwable ignored) {}
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found: " + path);
        } catch (Throwable t) {
            Log.e(TAG, "Serve exception for " + path, t);
            try { CrashLogger.err("Serve exception for " + path, t); } catch (Throwable ignored) {}
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Internal");
        }
    }

    private static String guessMime(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        if (lower.endsWith(".ts")) return "video/mp2t";
        return "application/octet-stream";
    }

    private static String readAll(InputStream in, String enc) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString(enc);
    }
}
