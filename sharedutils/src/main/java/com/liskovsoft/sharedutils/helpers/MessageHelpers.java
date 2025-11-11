package com.liskovsoft.sharedutils.helpers;

import android.content.Context;
import android.widget.Toast;

public class MessageHelpers {

    public static void showLongMessage(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }

    public static void showShortMessage(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }
}
