package com.steamoslite.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Plain HTTP(S) downloads for the runtime catalog and image. */
public final class Downloader {
    public interface ProgressListener {
        /** A 0..1 fraction, or -1 while the total size is unknown. */
        void onProgress(float fraction);
    }

    private Downloader() {}

    /**
     * Downloads {@code address} into {@code file}. With {@code resume} and a partial file already
     * there, asks for the rest with a Range request; a server that ignores it restarts the file.
     */
    public static boolean downloadFile(String address, File file, boolean resume, ProgressListener listener) {
        long existing = resume && file.exists() ? file.length() : 0;
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(address).openConnection();
            http.setConnectTimeout(15000);
            http.setReadTimeout(30000);
            if (existing > 0) http.setRequestProperty("Range", "bytes=" + existing + "-");
            int code = http.getResponseCode();
            boolean append = false;
            long done = 0;
            long total;
            if (existing > 0 && code == HttpURLConnection.HTTP_PARTIAL) {
                append = true;
                done = existing;
                total = existing + http.getContentLengthLong();
            } else if (existing > 0 && code == 416) {
                if (listener != null) listener.onProgress(1f);
                return true;
            } else if (code / 100 != 2) {
                return false;
            } else {
                total = http.getContentLengthLong();
            }
            try (InputStream in = http.getInputStream();
                 OutputStream out = new FileOutputStream(file, append)) {
                byte[] buffer = new byte[1 << 16];
                float last = -2f;
                for (int n; (n = in.read(buffer)) != -1; ) {
                    out.write(buffer, 0, n);
                    done += n;
                    if (listener == null) continue;
                    float fraction = total > 0 ? (float) done / total : -1f;
                    if (fraction < 0 || fraction - last >= 0.01f) {
                        last = fraction;
                        listener.onProgress(fraction);
                    }
                }
            }
            return total <= 0 || done >= total;
        } catch (Exception e) {
            return false;
        } finally {
            if (http != null) http.disconnect();
        }
    }

    /** The body at {@code address}, or null. */
    public static String downloadString(String address) {
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(address).openConnection();
            http.setConnectTimeout(15000);
            http.setReadTimeout(30000);
            if (http.getResponseCode() / 100 != 2) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(http.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; ) sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (http != null) http.disconnect();
        }
    }
}
