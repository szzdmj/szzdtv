package com.liskovsoft.sharedutils.configparser;

/**
 * Minimal ConfigParser stub. Real implementation should parse properties/assets.
 */
public class ConfigParser {
    public String get(String key) {
        return "";
    }

    public String get(String key, String def) {
        return def;
    }

    public String[] getArray(String key) {
        return new String[0];
    }

    public boolean getBoolean(String key) {
        return false;
    }

    public boolean getBoolean(String key, boolean def) {
        return def;
    }
}
