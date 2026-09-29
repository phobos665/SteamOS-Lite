package com.steamoslite.runtime

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Kills what an earlier session left behind. proot ends with the session only when its teardown
 * runs; when the session process is killed or crashes instead, proot, gamescope and Steam outlive
 * it, holding the rootfs, the GPU and Steam's lock, and the next session never comes up. At a
 * session's start nothing of ours should be running outside the app's own processes, so anything
 * else under the app's uid is a leftover. Processes are matched by uid, never by name.
 */
object OrphanReaper {
    private const val TAG = "OrphanReaper"

    fun reap(context: Context): Int {
        val me = android.os.Process.myPid()
        val uid = android.os.Process.myUid()
        val procs = File("/proc").listFiles().orEmpty().mapNotNull { it.name.toIntOrNull() }
            .filter { it > 1 && it != me && uidOf(it) == uid }
        val appProcs = procs.filter { cmdline(it).startsWith(context.packageName) }.toSet()
        var killed = 0
        for (pid in procs) {
            // The app's other processes, and what they started themselves, are alive on purpose.
            if (pid in appProcs || parentOf(pid) in appProcs) continue
            val name = cmdline(pid).ifEmpty { "?" }
            runCatching { android.os.Process.killProcess(pid) }
                .onSuccess { killed++; Log.i(TAG, "killed leftover $pid ($name)") }
                .onFailure { Log.w(TAG, "could not kill $pid ($name)", it) }
        }
        if (killed > 0) Log.i(TAG, "$killed leftover process(es) from an earlier session")
        return killed
    }

    private fun cmdline(pid: Int) = runCatching { File("/proc/$pid/cmdline").readText().substringBefore('\u0000') }.getOrDefault("")

    private fun status(pid: Int, key: String): String? = runCatching {
        File("/proc/$pid/status").useLines { lines -> lines.firstOrNull { it.startsWith("$key:") }?.substringAfter(':')?.trim() }
    }.getOrNull()

    private fun uidOf(pid: Int) = status(pid, "Uid")?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull() ?: -1

    private fun parentOf(pid: Int) = status(pid, "PPid")?.toIntOrNull() ?: -1
}
