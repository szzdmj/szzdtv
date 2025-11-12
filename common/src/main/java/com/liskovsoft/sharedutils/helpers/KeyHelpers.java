package com.liskovsoft.sharedutils.helpers;

/**
 * Minimal KeyHelpers stub. Extend if you need specific reserved-key logic.
 */
public final class KeyHelpers {
    private KeyHelpers() {}

    /**
     * Return true if the key code should be treated as a reserved/ambilight key.
     * Default: no reserved keys (safe for compilation). Adjust as needed.
     */
    public static boolean isAmbilightKey(int keyCode) {
        return false;
    }
}
