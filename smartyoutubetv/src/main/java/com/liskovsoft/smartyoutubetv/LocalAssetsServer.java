package com.liskovsoft.smartyoutubetv;

import android.content.res.AssetManager;
import android.util.Log;
import fi.iki.elonen.NanoHTTPD;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.Map;

public class LocalAssetsServer extends NanoHTTPD {
    private static final String TAG = "LocalAssetsServer";
    private final AssetManager assets;

    public LocalAssetsServer(int port, AssetManager assets) throws IOException {
        // bind explicitly to 0.0.0.0 to avoid IPv6-only localhost issues
        super(InetAddress.getByName("0.0.0.0"), port);
        this.assets = assets;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        String remote = session.getHeaders().get("remote-addr");
        Log.d(TAG, "Incoming request: uri=" + uri + ", remote=" + remote + ", method=" + session.getMethod());
        if (uri.equals("/") || uri.isEmpty()) uri = "/gjw.html";
        String path = uri.startsWith("/") ? uri.substring(1) : uri;
        try {
            InputStream is = assets.open(path);
            String mime = getMimeTypeForPath(path);
            Response res = newChunkedResponse(Response.Status.OK, mime, is);
            // CORS for debugging
            res.addHeader("Access-Control-Allow-Origin", "*");
            return res;
        } catch (IOException e) {
            Log.w(TAG, "Asset not found: " + path);
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
        } catch (Throwable t) {
            Log.e(TAG, "Serve exception for " + path, t);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Internal");
        }
    }

    private String getMimeTypeForPath(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".mp4")) return "video/mp4";
        return "application/octet-stream";
    }
}
