package com.liskovsoft.sharedutils.okhttp;

import java.io.IOException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Minimal OkHttp helper shim. Uses okhttp3.
 * Replace with full implementation when available.
 */
public final class OkHttpHelpers {
    private static final OkHttpClient CLIENT = new OkHttpClient();

    private OkHttpHelpers() {}

    public static Response executeGet(String url) throws IOException {
        Request req = new Request.Builder().url(url).get().build();
        return CLIENT.newCall(req).execute();
    }
}
