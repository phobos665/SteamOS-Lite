package com.steamoslite.runtime;

import android.content.Context;
import android.util.Log;

import com.steamoslite.util.Downloader;
import com.steamoslite.util.FileUtils;
import com.steamoslite.util.ResumableDownload;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.function.BooleanSupplier;

/**
 * Downloads and unpacks the Linux runtime rootfs into {@code files/linuxfs}. Nothing fetches it
 * until the user asks for it, and a session refuses to start without it.
 *
 * <p>The catalog row names the build, its URL and its sha256, so an interrupted or corrupted
 * download is caught before anything is written into the app's files directory.
 */
public final class RuntimeInstaller {
    private static final String TAG = "RuntimeInstaller";

    /**
     * Catalog row naming the current runtime build. Bannerlator's, which is where the image is built
     * and published (tools/linuxfs in that repo); change this one constant to host it elsewhere.
     */
    public static final String CATALOG_URL =
            "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/linuxfs.json";

    private static final String VERSION_FILE = ".version";

    public interface ProgressListener {
        /** {@code percent} is -1 while the size is unknown. */
        void onProgress(String stage, int percent);
    }

    public static final class Release {
        public final String version;
        public final String url;
        public final String sha256;
        public final long size;

        public Release(String version, String url, String sha256, long size) {
            this.version = version;
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    private RuntimeInstaller() {}

    /** The build currently unpacked, or null when the runtime is not installed. */
    public static String installedVersion(Context context) {
        File marker = new File(LinuxRuntime.rootDir(context), VERSION_FILE);
        if (!marker.isFile() || !LinuxRuntime.isInstalled(context)) return null;
        String v = FileUtils.readString(marker);
        return v == null ? null : v.trim();
    }

    /** The catalog's current build, or null when it cannot be reached or read. */
    public static Release fetchRelease() {
        String body = Downloader.downloadString(CATALOG_URL);
        if (body == null || body.isEmpty()) return null;
        try {
            JSONObject json = new JSONObject(body);
            String version = json.optString("version", "");
            String url = json.optString("url", "");
            String sha256 = json.optString("sha256", "");
            if (version.isEmpty() || url.isEmpty() || sha256.isEmpty()) return null;
            return new Release(version, url, sha256, json.optLong("size", 0L));
        } catch (Exception e) {
            Log.w(TAG, "catalog: " + e);
            return null;
        }
    }

    /**
     * The guest's home directory, carried across an update rather than replaced with the tarball's
     * empty one. Steam installs itself here - the client, the signed-in account and every
     * downloaded game - so replacing the rootfs wholesale used to delete all three and leave the
     * user re-downloading Steam and signing in again, with their library gone.
     */
    private static final String USER_DATA = "root";

    /** Where downloads wait until they are installed: app storage, which Android never clears on its own. */
    private static File downloadDir(Context context) {
        return new File(context.getFilesDir(), "downloads");
    }

    /** The archive for one release. Named by its checksum, so a partial is only ever resumed into the same file. */
    private static File archiveFor(Context context, Release release) {
        String tag = release.sha256.length() >= 16 ? release.sha256.substring(0, 16) : release.version;
        return new File(downloadDir(context), "linuxfs-" + tag + ".tar.zst");
    }

    /** Bytes of {@code release} already on disk from an earlier attempt, 0 when none. */
    public static long partialBytes(Context context, Release release) {
        File archive = archiveFor(context, release);
        return archive.isFile() ? archive.length() : 0L;
    }

    /**
     * Downloads {@code release} and replaces whatever is installed with it. Throws with a message a
     * user can act on when it cannot, leaving the existing runtime alone; the new rootfs is only moved
     * into place once it has been unpacked whole.
     *
     * <p>The download resumes: a failed connection is retried with backoff from the last byte on
     * disk, and the partial file outlives a failure or the process being killed, so calling this
     * again carries on where the last attempt stopped. It is deleted only once the install succeeds,
     * or if its checksum proves it wrong.
     *
     * <p>{@link #USER_DATA} survives the swap: the system is replaced, what the user put in it is
     * not. An update therefore keeps Steam, the login and the installed games.
     */
    public static void install(Context context, Release release, ProgressListener listener,
                               BooleanSupplier cancelled) throws IOException {
        File archive = archiveFor(context, release);
        // Partials of other releases are dead weight: a release is only ever resumed into its own file.
        File[] stale = downloadDir(context).listFiles();
        if (stale != null) {
            for (File f : stale) if (!f.equals(archive)) FileUtils.delete(f);
        }

        final int[] lastPercent = {0};
        ResumableDownload download = new ResumableDownload(release.url, archive, release.size, 8);
        boolean ok = download.run(new ResumableDownload.Listener() {
            @Override
            public void onProgress(long done, long total) {
                int percent = total > 0 ? (int) (done * 100 / total) : -1;
                lastPercent[0] = percent;
                listener.onProgress("Downloading", percent);
            }

            @Override
            public void onRetry(int attempt, long delayMs, String reason) {
                listener.onProgress("Connection problem, retrying in " + Math.max(1, delayMs / 1000)
                        + " s (attempt " + attempt + ")", lastPercent[0]);
            }
        }, cancelled::getAsBoolean);
        if (!ok) {
            throw new IOException("The download kept failing. It will continue from "
                    + (archive.length() / 1_000_000) + " MB when you try again.");
        }

        listener.onProgress("Verifying", -1);
        String actual;
        try {
            actual = sha256(archive);
        } catch (Exception e) {
            throw new IOException("Could not read the download: " + e.getMessage(), e);
        }
        if (!release.sha256.equalsIgnoreCase(actual)) {
            Log.w(TAG, "checksum mismatch: wanted " + release.sha256 + ", got " + actual);
            FileUtils.delete(archive);
            throw new IOException("The download was corrupted and has been discarded. Try again to download it fresh.");
        }

        // Unpack beside the live rootfs and swap, so a failure here cannot leave a half runtime that
        // isInstalled() would happily launch. The verified archive stays until this succeeds, so a
        // failed or interrupted unpack does not cost another download.
        File root = LinuxRuntime.rootDir(context);
        File staging = new File(root.getParentFile(), LinuxRuntime.DIR + ".new");
        FileUtils.delete(staging);
        if (!staging.mkdirs()) throw new IOException("Could not create " + staging);
        listener.onProgress("Extracting", -1);
        if (!extract(archive, staging, listener, cancelled)) {
            FileUtils.delete(staging);
            if (cancelled.getAsBoolean()) throw new ResumableDownload.Cancelled();
            throw new IOException("Unpacking the runtime failed. Check there is at least 3 GB free, then try again.");
        }
        FileUtils.writeString(new File(staging, VERSION_FILE), release.version);

        File old = new File(root.getParentFile(), LinuxRuntime.DIR + ".old");
        FileUtils.delete(old);
        if (root.isDirectory() && !root.renameTo(old)) {
            FileUtils.delete(staging);
            throw new IOException("Could not move the old runtime aside.");
        }

        // Carry the user's home over before the new rootfs takes the name. A rename inside the
        // same filesystem, so a 30 GB library costs nothing and cannot half-copy; the tarball's
        // own empty /root is dropped first so the rename has somewhere to land. If this fails
        // the update is abandoned and the previous runtime is put back untouched - shipping a
        // working system with the user's games gone is the worse outcome.
        File keptFrom = new File(old, USER_DATA);
        if (keptFrom.isDirectory()) {
            File keptTo = new File(staging, USER_DATA);
            FileUtils.delete(keptTo);
            if (!keptFrom.renameTo(keptTo)) {
                Log.w(TAG, "could not carry " + USER_DATA + " across the update; rolling back");
                FileUtils.delete(staging);
                old.renameTo(root);
                throw new IOException("Could not carry Steam and your games over; the old runtime was kept.");
            }
        }

        if (!staging.renameTo(root)) {
            if (old.isDirectory()) old.renameTo(root);
            throw new IOException("Could not move the new runtime into place.");
        }
        FileUtils.delete(old);
        FileUtils.delete(archive);
        if (!LinuxRuntime.isInstalled(context)) throw new IOException("The runtime unpacked but is incomplete.");
    }

    public static void uninstall(Context context) {
        FileUtils.delete(LinuxRuntime.rootDir(context));
    }

    /**
     * A whole-rootfs tar, which is not the shape the shared extractor handles: a distribution
     * rootfs is full of hard links (one binary under several names), and an entry written as an
     * empty file instead of its link target is a rootfs that boots to nothing. Symlinks, hard
     * links and the executable bit are all carried over here.
     */
    private static boolean extract(File archive, File destination, ProgressListener listener,
                                   BooleanSupplier cancelled) {
        long entries = 0;
        try (InputStream in = new ZstdCompressorInputStream(
                new BufferedInputStream(new FileInputStream(archive), 1 << 16));
             TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry;
            String base = destination.getCanonicalPath() + File.separator;
            while ((entry = tar.getNextTarEntry()) != null) {
                if (cancelled.getAsBoolean()) return false;
                File file = new File(destination, entry.getName());
                // Refuse anything that would land outside the runtime directory.
                if (!(file.getCanonicalPath() + (entry.isDirectory() ? File.separator : "")).startsWith(base)
                        && !file.getCanonicalPath().equals(destination.getCanonicalPath())) {
                    Log.w(TAG, "skipping entry outside the rootfs: " + entry.getName());
                    continue;
                }
                if (entry.isDirectory()) {
                    file.mkdirs();
                    continue;
                }
                File parent = file.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();

                if (entry.isSymbolicLink()) {
                    file.delete();
                    FileUtils.symlink(entry.getLinkName(), file.getAbsolutePath());
                } else if (entry.isLink()) {
                    // A hard link to an earlier entry. Link where the filesystem allows it and
                    // fall back to a copy, which costs space but always works.
                    File target = new File(destination, entry.getLinkName());
                    file.delete();
                    try {
                        Files.createLink(file.toPath(), target.toPath());
                    } catch (IOException | UnsupportedOperationException e) {
                        if (target.isFile()) {
                            Files.copy(target.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                } else if (entry.isFile()) {
                    try (OutputStream out = new FileOutputStream(file)) {
                        byte[] buffer = new byte[1 << 16];
                        int read;
                        while ((read = tar.read(buffer)) != -1) out.write(buffer, 0, read);
                    }
                    if ((entry.getMode() & 0111) != 0) file.setExecutable(true, false);
                } else {
                    continue; // device nodes and fifos: the runtime binds the real ones
                }
                if (++entries % 2000 == 0 && listener != null) {
                    listener.onProgress("Extracting", -1);
                }
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "extract", e);
            return false;
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 1 << 16)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
