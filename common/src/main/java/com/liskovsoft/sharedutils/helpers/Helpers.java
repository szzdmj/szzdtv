package com.liskovsoft.sharedutils.helpers;

import android.view.KeyEvent;

/**
 * Minimal helper stubs used to satisfy compilation.
 * Replace with the real sharedutils implementation when available.
 */
public final class Helpers {
    private Helpers() {}

    /**
     * Create a new KeyEvent based on an existing event but with a different key code.
     */
    public static KeyEvent newEvent(KeyEvent event, int newKeyCode) {
        if (event == null) {
            return null;
        }

        return new KeyEvent(
                event.getDownTime(),
                event.getEventTime(),
                event.getAction(),
                newKeyCode,
                event.getRepeatCount(),
                event.getMetaState()
        );
    }

    /**
     * Minimal stub for stacktrace checks if used elsewhere.
     */
    public static boolean checkStackTrace(String keyword) {
        return false;
    }
}
