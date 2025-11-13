package edu.mit.mobile.android.appupdater.downloadmanager;

import android.content.Context;
import android.net.Uri;

/**
 * Minimal stub for MyDownloadManager used by MyXWalkLibraryLoader.
 * This is a compile-time shim; implement real download behavior if you
 * plan to support dynamic Crosswalk library downloads.
 */
public class MyDownloadManager {
    private final Context mContext;

    public MyDownloadManager(Context ctx) {
        mContext = ctx;
    }

    public long enqueue(Uri uri, String destinationFileName) {
        // no-op stub: return a fake id
        return 0L;
    }

    public boolean isDownloaded(String destinationFileName) {
        return false;
    }

    public void cancel(long id) {
        // no-op
    }

    public static MyDownloadManager from(Context ctx) {
        return new MyDownloadManager(ctx);
    }
}
