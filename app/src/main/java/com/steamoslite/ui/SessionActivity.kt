package com.steamoslite.ui

import android.app.Activity
import android.app.AlertDialog
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
    private lateinit var controllers: Controllers
    private var session: Session? = null
    private var compositorStarted = false
    @Volatile private var firstFrame = false
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pickHighestRefreshMode()

        surface = SurfaceView(this)
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            textSize = 18f
            gravity = Gravity.CENTER
            text = "Starting SteamOS…"
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surface, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -1))
        })
        hideSystemBars()

        controllers = Controllers(this, Session.fakeInputDir(this))
        getSystemService(InputManager::class.java).registerInputDeviceListener(inputDevices, main)

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (!compositorStarted) {
                    compositorStarted = true
                    startCompositor(holder)
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
        WaylandCompositor.setFirstFrameListener {
            firstFrame = true
            main.post { status.visibility = View.GONE }
        }
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
        session = s
        thread(name = "SessionStart") {
            try {
                s.start()
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

    /**
     * Mirrors the session script's "== STEP" milestones onto the loading screen. A first run
     * downloads the Steam client before anything is drawn - a minute or two of black screen that
     * otherwise reads as a hang.
     */
    private fun watchProgress(log: File) {
        var offset = 0L
        while (!firstFrame && !ending) {
            Thread.sleep(1000)
            if (!log.isFile || log.length() == offset) continue
            try {
                RandomAccessFile(log, "r").use { raf ->
                    if (raf.length() < offset) offset = 0
                    raf.seek(offset)
                    val buf = ByteArray((raf.length() - offset).coerceAtMost(256 * 1024).toInt())
                    raf.readFully(buf)
                    offset += buf.size
                    val step = String(buf).lineSequence().mapNotNull { line ->
                        val at = line.indexOf("== STEP ")
                        if (at < 0) null else line.substring(at + 8).substringAfter(' ').trim()
                    }.lastOrNull()
                    if (step != null) main.post { if (!firstFrame) status.text = "Starting SteamOS…\n\n$step" }
                }
            } catch (_: Exception) {}
        }
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

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        controllers.onMotionEvent(event) || super.dispatchGenericMotionEvent(event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (controllers.onKeyEvent(event)) return true
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) confirmExit()
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

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle("Leave SteamOS?")
            .setMessage("Steam and any running game will be closed.")
            .setPositiveButton("Leave") { _, _ -> finishSession(null) }
            .setNegativeButton("Stay", null)
            .show()
    }

    // ---- Lifecycle

    private fun finishSession(message: String?) {
        if (ending) return
        ending = true
        message?.let { Log.i(TAG, it) }
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
        const val EXTRA_APP_ID = "app_id"
        /** gamescope's size. The client's interface is the most expensive thing it draws: 720p. */
        const val OUTPUT_WIDTH = 1280
        const val OUTPUT_HEIGHT = 720
    }
}
