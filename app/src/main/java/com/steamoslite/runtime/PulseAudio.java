package com.steamoslite.runtime;

import android.content.Context;
import android.util.Log;

import com.steamoslite.util.FileUtils;

import java.io.File;
import java.util.Arrays;

/**
 * The PulseAudio daemon the session plays through: the Steam client, its interface sounds and every
 * game Proton starts talk to it over a unix socket in the app's files directory (bound into the
 * session at its own path), and its module-aaudio-sink hands the audio to Android.
 *
 * <p>The daemon is Bannerlator's bionic PulseAudio 13 build. Its executable and libraries ship as
 * jniLibs so they land, executable, in the native library directory; the modules come from the
 * {@code pulseaudio.tzst} asset, unpacked by the session into {@link #workingDir}.
 *
 * <p>Trimmed from Bannerlator's PulseAudioComponent: no route-change recovery and no microphone.
 */
public final class PulseAudio {
    private static final String TAG = "PulseAudio";

    private final Context context;
    private Process process;

    public PulseAudio(Context context) {
        this.context = context.getApplicationContext();
    }

    public static File workingDir(Context context) {
        return new File(context.getFilesDir(), "pulseaudio");
    }

    /** The socket the session's PULSE_SERVER names. */
    public static File socket(Context context) {
        return new File(workingDir(context), "PS0");
    }

    public synchronized void start() {
        stop();
        File dir = workingDir(context);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        FileUtils.chmod(dir, 0771);
        //noinspection ResultOfMethodCallIgnored
        socket(context).delete();

        // ALWAYS volume=1.0: module-aaudio-sink defaults its volume to 0.0 without it and the sink
        // comes up silent. The game and the client control their own volume.
        String config = String.join("\n", Arrays.asList(
                "load-module module-native-protocol-unix auth-anonymous=1 auth-cookie-enabled=0 socket=\""
                        + socket(context).getPath() + "\"",
                "load-module module-aaudio-sink performance_mode=1 adaptive=1 volume=1.0",
                "set-default-sink AAudioSink"));
        FileUtils.writeString(new File(dir, "default.pa"), config);

        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        File modules = new File(dir, "modules/arm64");
        ProcessBuilder pb = new ProcessBuilder(
                nativeLibDir + "/libpulseaudio.so",
                "--system=false",
                "--disable-shm=true",
                "--fail=false",
                "-n", "--file=default.pa",
                "--daemonize=false",
                "--use-pid-file=false",
                "--exit-idle-time=-1",
                "--log-level=info",
                "--log-target=file:" + new File(dir, "pulse.log").getPath());
        pb.directory(dir);
        pb.environment().put("LD_LIBRARY_PATH", "/system/lib64:" + modules + ":" + nativeLibDir);
        pb.environment().put("HOME", dir.getPath());
        pb.environment().put("TMPDIR", context.getCacheDir().getPath());
        pb.redirectErrorStream(true);
        pb.redirectOutput(new File("/dev/null"));
        try {
            process = pb.start();
        } catch (Exception e) {
            Log.e(TAG, "could not start the PulseAudio daemon", e);
        }
    }

    public synchronized void stop() {
        if (process != null) {
            process.destroy();
            process = null;
        }
    }
}
