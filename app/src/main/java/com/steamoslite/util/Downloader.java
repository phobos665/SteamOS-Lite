package com.steamoslite.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Small HTTP(S) fetches (the runtime catalog). Large files go through {@link ResumableDownload}. */
public final class Downloader {
    private Downloader() {}

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
