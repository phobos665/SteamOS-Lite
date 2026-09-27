package com.steamoslite.runtime;

import android.util.Log;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Runs the session's one program - proot, with the session script inside it - for the length of the
 * session; the session ends when it exits. The command is a complete proot invocation from
 * {@link LinuxRuntime#command}.
 *
 * <p>Lifted from Bannerlator's LinuxProgramLauncherComponent (ported from WinNative, GPL-3.0).
 */
public class SessionProcess {
    private static final String TAG = "SessionProcess";

    private final List<String> command;
    private final Map<String, String> env;
    private final File workingDir;
    private final File outputLog;
    private final IntConsumer onExit;
    private final Object lock = new Object();
    private java.lang.Process process;
    private int pid = -1;

    /**
     * @param env        the host-side environment proot starts with (not the guest's: that is
     *                   {@code env -i ...} inside the command itself)
     * @param outputLog  where proot's own stdout/stderr go; the session script logs itself
     * @param onExit     called with the exit status when the session ends on its own
     */
    public SessionProcess(List<String> command, Map<String, String> env, File workingDir,
                          File outputLog, IntConsumer onExit) {
        this.command = command;
        this.env = env;
        this.workingDir = workingDir;
        this.outputLog = outputLog;
        this.onExit = onExit;
    }

    public void start() {
        synchronized (lock) {
            stop();
            Log.i(TAG, "exec " + String.join(" ", command));
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workingDir);
            pb.environment().putAll(env);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(outputLog));
            try {
                final java.lang.Process started = pb.start();
                process = started;
                pid = pidOf(started);
                Thread waiter = new Thread(() -> {
                    int status;
                    try {
                        status = started.waitFor();
                    } catch (InterruptedException e) {
                        return;
                    }
                    boolean ours;
                    synchronized (lock) {
                        ours = process == started;
                        if (ours) {
                            process = null;
                            pid = -1;
                        }
                    }
                    // A stop() we asked for is not the session ending on its own.
                    if (ours && onExit != null) onExit.accept(status);
                }, "SessionProcessWait");
                waiter.setDaemon(true);
                waiter.start();
            } catch (Exception e) {
                Log.e(TAG, "could not start the session", e);
                if (onExit != null) onExit.accept(-1);
            }
        }
    }

    public boolean isRunning() {
        synchronized (lock) {
            return process != null;
        }
    }

    /** java.lang.Process has no public pid before API 33; Android's implementation keeps it here. */
    private static int pidOf(java.lang.Process p) {
        try {
            java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            return f.getInt(p);
        } catch (Exception e) {
            return -1;
        }
    }

    /** How long proot is given to take its own tree down before it is killed outright. */
    private static final long GRACE_MS = 1200L;

    /**
     * Ends the session.
     *
     * <p>This used to be a single {@code Process.killProcess(pid)} on proot, on the understanding
     * that {@code --kill-on-exit} would take the tree with it. It does not: that is proot's own
     * cleanup, and SIGKILL is the one signal it cannot catch, so the cleanup never runs. What was
     * left behind was worse than an ordinary leak - every process under proot is traced by it, and
     * an untraced survivor gets ENOSYS from every syscall proot used to answer. It cannot fail and
     * it cannot proceed, so it spins. One such orphan was found holding a whole core for
     * seventy-four minutes with seven unreaped children, and it is the likeliest source of the
     * "Function not implemented" failures seen on both devices.
     *
     * <p>So proot is asked first and killed only if it does not go, and anything still standing
     * afterwards is swept. The tree is read <em>before</em> proot dies, because once it is gone its
     * children are reparented to init and nothing distinguishes them from the rest of the app.
     */
    public void stop() {
        synchronized (lock) {
            if (process == null) return;
            java.lang.Process p = process;
            int prootPid = pid;
            process = null;
            pid = -1;
            if (prootPid <= 0) {
                p.destroyForcibly();
                return;
            }
            List<long[]> tree = descendants(prootPid);
            android.os.Process.sendSignal(prootPid, 15); // SIGTERM
            if (!waitForExit(prootPid, GRACE_MS)) {
                Log.w(TAG, "proot " + prootPid + " did not exit on SIGTERM; killing it");
                android.os.Process.killProcess(prootPid);
            }
            sweep(tree);
        }
    }

    /**
     * Every process below {@code root}, each as {@code {pid, start time}}. The start time is kept
     * so the sweep cannot kill a stranger: pids are reused, and by the time we look again the
     * number may belong to something else entirely.
     */
    private static List<long[]> descendants(int root) {
        Map<Integer, Integer> parents = new HashMap<>();
        Map<Integer, Long> started = new HashMap<>();
        Map<Integer, List<Integer>> children = new HashMap<>();
        File[] entries = new File("/proc").listFiles();
        if (entries != null) {
            for (File entry : entries) {
                int candidate;
                try {
                    candidate = Integer.parseInt(entry.getName());
                } catch (NumberFormatException e) {
                    continue; // not a process
                }
                long[] stat = readStat(candidate);
                if (stat == null) continue;
                int parent = (int) stat[0];
                parents.put(candidate, parent);
                started.put(candidate, stat[1]);
                List<Integer> kids = children.get(parent);
                if (kids == null) {
                    kids = new ArrayList<>();
                    children.put(parent, kids);
                }
                kids.add(candidate);
            }
        }
        List<long[]> out = new ArrayList<>();
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(root);
        int myPid = android.os.Process.myPid();
        while (!queue.isEmpty()) {
            List<Integer> kids = children.get(queue.poll());
            if (kids == null) continue;
            for (int kid : kids) {
                // Guards against a cycle in a racing read, and against ever queueing ourselves.
                if (kid <= 1 || kid == myPid || kid == root) continue;
                Long when = started.get(kid);
                if (when == null) continue;
                out.add(new long[]{kid, when});
                queue.add(kid);
            }
        }
        return out;
    }

    /** {@code {ppid, start time}} from {@code /proc/pid/stat}, or null. */
    private static long[] readStat(int pid) {
        // Read defensively rather than through FileUtils.readString: that throws on a file it
        // cannot read, and a /proc entry can disappear between listing it and opening it - which
        // is not an error here, it is the normal case for a process that has just exited.
        String stat;
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(
                    new File("/proc/" + pid + "/stat").toPath());
            stat = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
        if (stat.isEmpty()) return null;
        // The second field is the executable name in parentheses and may itself contain spaces and
        // parentheses, so the fields are counted from the LAST close paren, never split from the
        // start. After it: state, ppid, ... with start time the twentieth.
        int close = stat.lastIndexOf(')');
        if (close < 0 || close + 2 >= stat.length()) return null;
        String[] fields = stat.substring(close + 2).trim().split("\\s+");
        if (fields.length < 20) return null;
        try {
            return new long[]{Long.parseLong(fields[1]), Long.parseLong(fields[19])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** True once {@code pid} is gone, false if it is still there after {@code timeout}. */
    private static boolean waitForExit(int pid, long timeout) {
        long deadline = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < deadline) {
            if (!new File("/proc/" + pid).exists()) return true;
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !new File("/proc/" + pid).exists();
    }

    /** Kills whatever of the captured tree outlived proot, skipping any pid since recycled. */
    private static void sweep(List<long[]> tree) {
        int killed = 0;
        for (long[] entry : tree) {
            int pid = (int) entry[0];
            long[] stat = readStat(pid);
            if (stat == null) continue;      // already gone
            if (stat[1] != entry[1]) continue; // same number, different process
            android.os.Process.killProcess(pid);
            killed++;
        }
        if (killed > 0) Log.i(TAG, "swept " + killed + " process(es) proot left behind");
    }
}
