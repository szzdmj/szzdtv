package com.liskovsoft.sharedutils.okhttp;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

/**
 * Minimal OkHttp helper shim. Uses okhttp3; add okhttp dependency to module build.gradle.
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
