package com.liskovsoft.sharedutils.helpers;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal FileHelpers shim used by browser module code paths.
 * This is intentionally small — add methods as needed when actual usages show up.
 */
public class FileHelpers {
    public static boolean ensureDir(File dir) {
        if (dir == null) return false;
        if (dir.exists()) return dir.isDirectory();
        return dir.mkdirs();
    }

    public static void writeBytesToFile(File file, byte[] data) throws IOException {
        if (file == null) throw new IOException("file is null");
        File parent = file.getParentFile();
        if (parent != null) ensureDir(parent);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
            fos.flush();
        }
    }

    public static String readAll(InputStream in) throws IOException {
        if (in == null) return null;
        byte[] buffer = new byte[8192];
        StringBuilder sb = new StringBuilder();
        int n;
        while ((n = in.read(buffer)) != -1) {
            sb.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    public static boolean delete(File f) {
        if (f == null) return false;
        return f.delete();
    }
}
