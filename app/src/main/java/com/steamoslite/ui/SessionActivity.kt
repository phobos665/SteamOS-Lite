package com.steamoslite.ui

import android.app.Activity
import android.graphics.Color
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.steamoslite.input.Controllers
import com.steamoslite.input.OnScreenController
import com.steamoslite.runtime.Session
import com.steamoslite.util.FileUtils
import com.steamoslite.util.TarZstd
import com.steamoslite.wayland.WaylandCompositor
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread

/**
 * The SteamOS screen: a SurfaceView the compositor draws gamescope into, with the handheld's
 * controls, touch and keyboard fed back in.
 *
 * Runs in its own process (":session"). The compositor and everything the session starts are
 * process-wide and built for one run, so a session ends by ending the process - the same thing
 * Bannerlator does when it returns from a game.
 */
class SessionActivity : Activity() {
    private lateinit var surface: SurfaceView
    private lateinit var status: TextView
    private lateinit var keyboard: KeyboardBridge
    private lateinit var quickMenu: QuickMenu
    private lateinit var onScreen: OnScreenController
    private lateinit var controllers: Controllers
    private var session: Session? = null
    private lateinit var timeline: StartupTimeline
    private var compositorStarted = false
    @Volatile private var ending = false
    private val main = Handler(Looper.getMainLooper())

    private val vsync = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (ending) return
            WaylandCompositor.nativeVsync(frameTimeNanos)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val inputDevices = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {}
        override fun onInputDeviceChanged(deviceId: Int) {}
        override fun onInputDeviceRemoved(deviceId: Int) = controllers.onDeviceRemoved()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        timeline = StartupTimeline(intent.getLongExtra(EXTRA_TAPPED_AT, System.currentTimeMillis()))
        timeline.mark("session screen created")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The keyboard slides over the picture; resizing the surface would resize gamescope's output.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        pickHighestRefreshMode()

        surface = SurfaceView(this)
        status = loadingView(this)
        keyboard = KeyboardBridge(this)
        controllers = Controllers(this, Session.fakeInputDir(this))
        onScreen = OnScreenController(this) { controllers.setOnScreen(it) }.apply {
            visibility = if (prefs.getBoolean(PREF_ON_SCREEN, false)) View.VISIBLE else View.GONE
        }
        quickMenu = QuickMenu(this).apply {
            setItems("SteamOS", listOf(
                QuickMenu.Item({ if (keyboard.keyboardVisible) "Hide keyboard" else "Show keyboard" }) { toggleKeyboard() },
                QuickMenu.Item({ if (onScreenShown) "Hide on-screen controller" else "Show on-screen controller" }) {
                    setOnScreenShown(!onScreenShown)
                },
                QuickMenu.Item({ "Exit SteamOS" }) { finishSession(null) },
                QuickMenu.Item({ "Close menu" }) {},
            ))
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surface, FrameLayout.LayoutParams(-1, -1))
            addView(keyboard, FrameLayout.LayoutParams(1, 1))
            addView(onScreen, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -1))
            addView(quickMenu, FrameLayout.LayoutParams(-1, -1))
        })
        hideSystemBars()

        getSystemService(InputManager::class.java).registerInputDeviceListener(inputDevices, main)

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (!compositorStarted) {
                    compositorStarted = true
                    startCompositor(holder)
                    timeline.mark("compositor started")
                    startSession()
                } else {
                    WaylandCompositor.nativeSetSurface(holder.surface)
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) = WaylandCompositor.nativeSetSurface(null)
        })
        surface.setOnTouchListener { v, ev -> onSurfaceTouch(v, ev) }
    }

    private fun startCompositor(holder: SurfaceHolder) {
        val runtimeDir = Session.xdgRuntimeDir(this).apply { mkdirs() }
        // The compositor sends this keymap to wl_keyboard clients so they can read our evdev codes.
        assets.open("wayland/keymap.xkb").use { i -> File(runtimeDir, "keymap.xkb").outputStream().use { i.copyTo(it) } }
        val driver = bundledDriver()
        WaylandCompositor.nativeSetOutputRefreshRate(refreshHz().toFloat())
        WaylandCompositor.nativeSetOutputSize(OUTPUT_WIDTH, OUTPUT_HEIGHT)
        WaylandCompositor.nativeStartWithSurface(
            holder.surface, runtimeDir.path, driver?.first, driver?.second, applicationInfo.nativeLibraryDir,
        )
        Choreographer.getInstance().postFrameCallback(vsync)
    }

    private fun startSession() {
        // Pads first, so Steam sees them in its very first device scan.
        controllers.start()
        val appId = intent.getStringExtra(EXTRA_APP_ID)
        val s = Session(this, appId, OUTPUT_WIDTH, OUTPUT_HEIGHT, refreshHz()) { status ->
            main.post { finishSession("Steam exited ($status)") }
        }
        s.onMark = { timeline.mark(it) }
        session = s
        thread(name = "SessionStart") {
            try {
                s.start()
                timeline.mark("session started (proot and the session script running)")
                s.logDir?.let { watchProgress(File(it, "session.log")) }
            } catch (e: Exception) {
                Log.e(TAG, "session did not start", e)
                main.post {
                    status.text = "SteamOS could not start:\n${e.message}"
                    main.postDelayed({ finishSession(null) }, 4000)
                }
            }
        }
    }

    /** New complete lines appended to a log since the last read. */
    private class Tail(private val file: File, startAtEnd: Boolean) {
        private var offset = if (startAtEnd && file.isFile) file.length() else 0L

        fun newLines(): List<String> {
            if (!file.isFile) return emptyList()
            val len = file.length()
            if (len < offset) offset = 0 // rotated or rewritten
            if (len == offset) return emptyList()
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buf = ByteArray((len - offset).coerceAtMost(256 * 1024).toInt())
                raf.readFully(buf)
                val text = String(buf)
                val end = text.lastIndexOf('\n')
                if (end < 0) return emptyList()
                offset += text.substring(0, end + 1).toByteArray().size
                return text.substring(0, end).lines()
            }
        }
    }

    /**
     * Keeps the loading screen up, with something true on it, until Steam's interface is running.
     *
     * The session script's "== STEP" milestones cover the runtime and the client download. After
     * "starting the Steam client", the first boot of a fresh client downloads Valve's own update
     * (hundreds of MB) with nothing on screen, so Steam's bootstrap log is followed too. The screen
     * goes once steamwebhelper - the process that draws Big Picture - has been running a few seconds.
     * gamescope's first frame is no signal: it presents black long before Steam draws. A tap
     * dismisses the loading screen whenever the client has started, in case none of this fires.
     */
    private fun watchProgress(log: File) {
        val session = Tail(log, startAtEnd = false)
        val bootstrap = Tail(
            File(com.steamoslite.runtime.LinuxRuntime.rootDir(this), "root/.local/share/Steam/logs/bootstrap_log.txt"),
            startAtEnd = true,
        )
        var step = ""
        var detail = ""
        var clientStarted = false
        var helperSeenAt = 0L
        // Launched for one game: Steam boots its whole interface before it acts on the rungameid
        // link, so the loading screen stays until the game itself is running rather than dropping
        // onto Big Picture's home while the game is still being prepared.
        val appId = intent.getStringExtra(EXTRA_APP_ID)
        var launchSeenAt = 0L
        var gameSeenAt = 0L
        while (!ending) {
            Thread.sleep(1000)
            try {
                session.newLines().mapNotNull { line ->
                    val at = line.indexOf("== STEP ")
                    if (at < 0) return@mapNotNull null
                    val rest = line.substring(at + 8)
                    rest.substringAfter(' ').trim().also { timeline.step(rest.substringBefore(' '), it) }
                }.lastOrNull()?.let {
                    step = it
                    detail = ""
                    if (it.startsWith("starting the Steam client") && !clientStarted) {
                        clientStarted = true
                        main.post {
                            status.setOnClickListener {
                                timeline.mark("loading screen dismissed with a tap")
                                status.visibility = View.GONE
                            }
                        }
                    }
                }
                if (clientStarted) {
                    val fresh = bootstrap.newLines()
                    fresh.filter { it.startsWith("[") }.forEach(timeline::bootstrap)
                    fresh.lastOrNull { it.isNotBlank() }?.let {
                        // "[2026-09-27 12:00:00] Downloading update (12,345 of 665,432 KB)..."
                        detail = it.substringAfter("] ").trim()
                    }
                }
            } catch (_: Exception) {}

            if (clientStarted && steamInterfaceRunning()) {
                if (helperSeenAt == 0L) {
                    helperSeenAt = System.currentTimeMillis()
                    timeline.mark("steamwebhelper (Steam's interface) running")
                }
                val now = System.currentTimeMillis()
                val ready = if (appId == null) {
                    now - helperSeenAt >= 5000
                } else {
                    val game = gameProgress(appId)
                    if (game >= GAME_LAUNCHING && launchSeenAt == 0L) {
                        launchSeenAt = now
                        timeline.mark("Steam launching app $appId")
                        step = "Steam is starting the game"
                    }
                    if (game >= GAME_RUNNING && gameSeenAt == 0L) {
                        gameSeenAt = now
                        timeline.mark("game process running")
                        step = "The game is starting"
                    }
                    // A game whose window is on the way; a native Linux game, which has no .exe to
                    // see; or Steam waiting on something the user has to see (an update, a dialog).
                    (gameSeenAt > 0 && now - gameSeenAt >= 3000) ||
                        (launchSeenAt > 0 && now - launchSeenAt >= 30_000) ||
                        now - helperSeenAt >= 120_000
                }
                if (ready) {
                    timeline.mark("loading screen hidden")
                    timeline.write(this@SessionActivity.session?.logDir)
                    main.post { status.visibility = View.GONE }
                    return
                }
            } else {
                helperSeenAt = 0L
            }

            val text = buildString {
                append(if (appId == null) "Starting SteamOS…" else "Starting your game…")
                if (step.isNotEmpty()) append("\n\n").append(step)
                if (detail.isNotEmpty()) append("\n").append(detail)
                if (clientStarted) {
                    append("\n\nThe first start updates Steam itself and can take several minutes.")
                    append("\nTap to show the screen anyway.")
                }
            }
            main.post { if (status.visibility == View.VISIBLE) status.text = text }
        }
    }

    /**
     * How far a launch of [appId] has got: GAME_LAUNCHING once Steam's reaper runs it
     * ("SteamLaunch AppId=<id>"), GAME_RUNNING once a Windows program outside Wine's own system
     * folders is running, which is the game (or its launcher).
     */
    private fun gameProgress(appId: String): Int {
        var progress = 0
        for (p in File("/proc").listFiles() ?: return 0) {
            if (p.name.firstOrNull()?.isDigit() != true) continue
            val args = try {
                File(p, "cmdline").readBytes().toString(Charsets.UTF_8).split('\u0000')
            } catch (_: Exception) { continue }
            if (args.any { it == "AppId=$appId" }) progress = maxOf(progress, GAME_LAUNCHING)
            val exe = args.firstOrNull().orEmpty().lowercase()
            if (exe.endsWith(".exe") && ":\\" in exe && "\\windows\\" !in exe) return GAME_RUNNING
        }
        return progress
    }

    /** True while a steamwebhelper process - Steam's interface - is alive in the session. */
    private fun steamInterfaceRunning(): Boolean {
        val procs = File("/proc").listFiles() ?: return false
        for (p in procs) {
            if (!p.name.all(Char::isDigit)) continue
            try {
                // Chromium may rewrite its command line into one space-separated string, so match
                // anywhere - but not the session script's own `pgrep -f steamwebhelper|...`.
                val cmd = String(File(p, "cmdline").readBytes())
                if ("steamwebhelper" in cmd && "pgrep" !in cmd) return true
            } catch (_: Exception) {}
        }
        return false
    }

    /** The bundled Turnip, unpacked once per APK version: (driver dir with trailing /, library). */
    private fun bundledDriver(): Pair<String, String>? {
        val dir = File(filesDir, "drivers/turnip")
        val marker = File(dir, ".apk-version")
        val version = packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString()
        try {
            if (FileUtils.readString(marker)?.trim() != version) {
                FileUtils.delete(dir)
                TarZstd.extractAsset(this, "turnip.tzst", dir)
                FileUtils.writeString(marker, version)
            }
            val library = JSONObject(FileUtils.readString(File(dir, "meta.json")) ?: "{}").optString("libraryName")
            if (library.isNotEmpty()) return dir.path + "/" to library
        } catch (e: Exception) {
            Log.e(TAG, "could not unpack the bundled Turnip driver", e)
        }
        return null
    }

    // ---- Input

    /** A finger is the mouse, absolutely positioned: tap to click, drag to scroll a list. */
    private fun onSurfaceTouch(v: View, ev: MotionEvent): Boolean {
        if (v.width <= 0 || v.height <= 0) return true
        val x = (ev.x.coerceIn(0f, v.width.toFloat()) / v.width * WaylandCompositor.INPUT_WIDTH).toInt()
        val y = (ev.y.coerceIn(0f, v.height.toFloat()) / v.height * WaylandCompositor.INPUT_HEIGHT).toInt()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                WaylandCompositor.nativeSendPointer(WaylandCompositor.POINTER_MOVE, x, y)
                WaylandCompositor.nativeSendPointer(WaylandCompositor.POINTER_DOWN, x, y)
            }
            MotionEvent.ACTION_MOVE -> WaylandCompositor.nativeSendPointer(WaylandCompositor.POINTER_MOVE, x, y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                WaylandCompositor.nativeSendPointer(WaylandCompositor.POINTER_UP, x, y)
        }
        return true
    }

    /**
     * The on-screen controller sees every touch first and keeps the gestures that start on its
     * controls; everything else goes on to the views (the touch mouse on the surface, the menu).
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!quickMenu.isOpen && onScreen.handleTouch(event)) return true
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // While the menu is open the stick moves its focus (Android turns it into D-pad presses).
        if (quickMenu.isOpen) return super.dispatchGenericMotionEvent(event)
        return controllers.onMotionEvent(event) || super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (quickMenu.isOpen) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) quickMenu.close()
                return true
            }
            if (quickMenu.onPadKey(event)) return true
            return super.dispatchKeyEvent(event)
        }
        if (controllers.onKeyEvent(event)) return true
        // Back (the system gesture, or a handheld's back button) opens the quick menu. While the
        // Android keyboard is up, back reaches the keyboard first and closes it instead.
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) quickMenu.open()
            return true
        }
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN ||
            code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_HOME
        ) return super.dispatchKeyEvent(event)
        val evdev = Keys.toEvdev(code).takeIf { it > 0 } ?: event.scanCode
        if (evdev > 0) {
            // The guest repeats held keys itself; Android's repeats are dropped.
            if (event.repeatCount == 0) when (event.action) {
                KeyEvent.ACTION_DOWN -> WaylandCompositor.nativeSendKey(evdev, 1)
                KeyEvent.ACTION_UP -> WaylandCompositor.nativeSendKey(evdev, 0)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private val prefs by lazy { getSharedPreferences("session", MODE_PRIVATE) }
    private val onScreenShown get() = onScreen.visibility == View.VISIBLE

    /** Shows or hides the touch gamepad; the choice is kept for the next session. */
    private fun setOnScreenShown(shown: Boolean) {
        onScreen.releaseAll()
        onScreen.visibility = if (shown) View.VISIBLE else View.GONE
        if (!shown) controllers.setOnScreen(null)
        prefs.edit().putBoolean(PREF_ON_SCREEN, shown).apply()
    }

    private fun toggleKeyboard() {
        if (keyboard.keyboardVisible) keyboard.hide() else keyboard.show()
    }

    // ---- Lifecycle

    private fun finishSession(message: String?) {
        if (ending) return
        ending = true
        message?.let { Log.i(TAG, it) }
        timeline.mark("session ended" + (message?.let { ": $it" } ?: ""))
        timeline.write(session?.logDir)
        thread(name = "SessionStop") {
            session?.stop()
            controllers.stop()
            main.post {
                finish()
                // Everything the session started is process-wide; start the next one clean.
                main.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 300)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        getSystemService(InputManager::class.java).unregisterInputDeviceListener(inputDevices)
        if (!ending) {
            // Destroyed without finishSession (task swiped away): take the session down with us.
            ending = true
            session?.stop()
            controllers.stop()
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    /** Asks for the panel's fastest mode; gamescope and games are told the same rate. */
    @Suppress("DEPRECATION")
    private fun pickHighestRefreshMode() {
        val display = windowManager.defaultDisplay ?: return
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }

    @Suppress("DEPRECATION")
    private fun refreshHz(): Int {
        val display = windowManager.defaultDisplay ?: return 60
        return display.supportedModes.maxOfOrNull { it.refreshRate }?.let { Math.round(it) } ?: 60
    }

    companion object {
        private const val TAG = "SessionActivity"

        /** The loading screen shown over the surface until SteamOS is up. */
        fun loadingView(context: android.content.Context) = TextView(context).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            textSize = 18f
            gravity = Gravity.CENTER
            text = "Starting SteamOS…"
        }
        const val EXTRA_APP_ID = "app_id"
        const val EXTRA_TAPPED_AT = "tapped_at"
        private const val GAME_LAUNCHING = 1
        private const val GAME_RUNNING = 2
        private const val PREF_ON_SCREEN = "onScreenController"
        /** gamescope's size. The client's interface is the most expensive thing it draws: 720p. */
        const val OUTPUT_WIDTH = 1280
        const val OUTPUT_HEIGHT = 720
    }
}
