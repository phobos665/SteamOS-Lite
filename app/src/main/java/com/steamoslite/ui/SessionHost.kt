package com.steamoslite.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import com.steamoslite.input.Controllers
import com.steamoslite.runtime.LinuxRuntime
import com.steamoslite.runtime.Session
import com.steamoslite.runtime.Settings
import com.steamoslite.wayland.WaylandCompositor
import java.io.File
import kotlin.concurrent.thread

/**
 * The running session, held by the :session process rather than by the screen showing it. With
 * "Keep SteamOS running" on, leaving the screen leaves all of this up under [SessionKeepAlive];
 * coming back hands the compositor a new surface, and a game started meanwhile goes to the
 * running client instead of a new boot.
 */
internal object SessionHost {
    var session: Session? = null
        private set
    private var controllers: Controllers? = null
    var compositorStarted = false
    var keepRunning = false
        private set
    @Volatile var ending = false
        private set

    /** Told when the session has ended, so the screen showing it can close. */
    var onEnded: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    val running get() = session != null && !ending

    private val vsync = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (ending) return
            WaylandCompositor.nativeVsync(frameTimeNanos)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Starts feeding the compositor the display's vsync; call once, on the main thread. */
    fun startVsync() = Choreographer.getInstance().postFrameCallback(vsync)

    fun controllers(context: Context): Controllers =
        controllers ?: Controllers(context.applicationContext, Session.fakeInputDir(context)).also { controllers = it }

    fun begin(context: Context, session: Session) {
        this.session = session
        keepRunning = Settings.keepRunning(context)
        if (keepRunning) SessionKeepAlive.start(context)
    }

    /**
     * Asks the running client to start [appId]. The session script picks the line up from
     * ~/.bl-launch within a second and hands it to the client.
     */
    fun launch(context: Context, appId: String) {
        File(LinuxRuntime.rootDir(context), "root/.bl-launch").appendText("steam://rungameid/$appId\n")
    }

    /** Takes the session down and ends the process: everything it started is process-wide. */
    fun end(context: Context) {
        if (ending) return
        ending = true
        val app = context.applicationContext
        thread(name = "SessionStop") {
            session?.stop()
            controllers?.stop()
            main.post {
                onEnded?.invoke()
                SessionKeepAlive.stop(app)
                main.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 300)
            }
        }
    }
}
