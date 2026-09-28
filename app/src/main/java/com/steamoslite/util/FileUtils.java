package com.steamoslite.util;

import android.system.ErrnoException;
import android.system.Os;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** The handful of file helpers the runtime needs. Mirrors Bannerlator's FileUtils where it overlaps. */
public final class FileUtils {
    private FileUtils() {}

    /** The file's contents, or null when it cannot be read. */
    public static String readString(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    public static boolean writeString(File file, String data) {
        try {
            Files.write(file.toPath(), data.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static void symlink(String linkTarget, String linkFile) {
        try {
            //noinspection ResultOfMethodCallIgnored
            new File(linkFile).delete();
            Os.symlink(linkTarget, linkFile);
        } catch (ErrnoException ignored) {
        }
    }

    /** Deletes a file or a whole tree. A symlink is removed, never followed. */
    public static boolean delete(File target) {
        if (target == null) return false;
        if (target.isDirectory() && !Files.isSymbolicLink(target.toPath()) && !clear(target)) return false;
        return target.delete() || !target.exists();
    }

    /** Deletes everything inside a directory, keeping the directory. */
    public static boolean clear(File dir) {
        if (dir == null) return false;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (!delete(file)) return false;
            }
        }
        return true;
    }

    public static void chmod(File file, int mode) {
        try {
            Os.chmod(file.getAbsolutePath(), mode);
        } catch (ErrnoException ignored) {
        }
    }
}
