package com.liskovsoft.smartyoutubetv.fragments;

import android.content.Context;
import android.view.View;

/**
 * Minimal LoadingManager stub.
 * Real implementation likely manages loading overlays; this stub provides basic API surface.
 */
public class LoadingManager {
    private final Context mContext;

    public LoadingManager(Context ctx) {
        mContext = ctx;
    }

    public void showLoading(View parent) {
        // no-op stub
    }

    public void hideLoading() {
        // no-op stub
    }
}
