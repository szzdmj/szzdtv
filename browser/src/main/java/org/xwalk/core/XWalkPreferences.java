package org.xwalk.core;

/**
 * Minimal shim for Crosswalk XWalkPreferences.
 * Provides no-op setters/getters so code referencing XWalkPreferences compiles.
 */
public class XWalkPreferences {
    public static void setValue(String key, boolean value) {
        // no-op shim
    }

    public static boolean hasValue(String key) {
        return false;
    }

    public static boolean getValue(String key) {
        return false;
    }

    public static void setIntegerValue(String key, int value) {
        // no-op
    }

    public static int getIntegerValue(String key) {
        return 0;
    }
}
