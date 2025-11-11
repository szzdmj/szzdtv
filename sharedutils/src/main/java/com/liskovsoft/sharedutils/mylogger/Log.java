package com.liskovsoft.sharedutils.mylogger;

public class Log {

    public static void d(String tag, String msg) {
        android.util.Log.d(tag, msg);
    }

    public static void e(String tag, String msg) {
        android.util.Log.e(tag, msg);
    }

    public static void e(String tag, Throwable t) {
        android.util.Log.e(tag, t.getMessage(), t);
    }

    public static void i(String tag, String msg) {
        android.util.Log.i(tag, msg);
    }

    public static void w(String tag, String msg) {
        android.util.Log.w(tag, msg);
    }

    public static void v(String tag, String msg) {
        android.util.Log.v(tag, msg);
    }
}
