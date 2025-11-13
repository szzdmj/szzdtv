package org.xwalk.core;

/**
 * Minimal shim for Crosswalk XWalkPreferences.
 */
public class XWalkPreferences {
    public static void setValue(String key, boolean value) { }
    public static boolean hasValue(String key) { return false; }
    public static boolean getValue(String key) { return false; }
    public static void setIntegerValue(String key, int value) { }
    public static int getIntegerValue(String key) { return 0; }
}
