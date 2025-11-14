package com.liskovsoft.smartyoutubetv.fragments;

import android.content.Context;
import android.view.View;

/** Minimal LoadingManager stub. */
public class LoadingManager {
    private final Context mContext;
    public LoadingManager(Context ctx) { mContext = ctx; }
    public void showLoading(View parent) {}
    public void hideLoading() {}
}
