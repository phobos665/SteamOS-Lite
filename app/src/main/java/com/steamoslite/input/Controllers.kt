package com.steamoslite.input

import android.content.Context
import android.net.LocalServerSocket
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max

/**
 * Physical controllers -> the session's virtual Xbox 360 pads.
 *
 * Android delivers the handheld's built-in pad (and any Bluetooth/USB pad) to the activity as key
 * and motion events. Each physical pad gets one of four slots; its state is written into that slot's
 * [FakeInputWriter] ring, which the session's libfakeinput.so serves to Steam as /dev/input/eventN.
 * Rumble comes back the other way over an abstract socket.
 *
 * A pared-down WinHandler + ExternalController from Bannerlator: no on-screen controls, no remapping,
 * no gyro, no slot pinning.
 */
class Controllers(context: Context, private val fakeInputDir: File) {
    private val appContext = context.applicationContext
    private val slotByDescriptor = HashMap<String, Int>()
    private val writers = arrayOfNulls<FakeInputWriter>(MAX_SLOTS)
    private val states = Array(MAX_SLOTS) { GamepadState() }

    @Volatile private var rumbleServer: LocalServerSocket? = null
    @Volatile private var rumbleRunning = false

    /** Claims a slot for every pad already connected, so Steam sees them from its first scan. */
    fun start() {
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            if (isGameController(device)) slotFor(device)
        }
        startRumbleListener()
    }

    fun stop() {
        rumbleRunning = false
        try { rumbleServer?.close() } catch (_: IOException) {}
        rumbleServer = null
        for (i in 0 until MAX_SLOTS) {
            writers[i]?.destroy()
            writers[i] = null
        }
        slotByDescriptor.clear()
        FakeInputWriter.releaseAllRingSlots()
    }

    /** A pad was unplugged: Steam sees it removed. */
    fun onDeviceRemoved() {
        val present = InputDevice.getDeviceIds().mapNotNull { InputDevice.getDevice(it)?.descriptor }.toSet()
        val gone = slotByDescriptor.filterKeys { it !in present }
        for ((descriptor, slot) in gone) {
            slotByDescriptor.remove(descriptor)
            writers[slot]?.destroy()
            writers[slot] = null
            states[slot].reset()
            Log.i(TAG, "slot $slot released")
        }
    }

    /** True when the event came from a pad and was consumed. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        if (!isGameController(device)) return false
        val action = event.action
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return true
        val slot = slotFor(device) ?: return true
        val state = states[slot]
        val pressed = action == KeyEvent.ACTION_DOWN
        when (val code = event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> state.dpad[0] = pressed
            KeyEvent.KEYCODE_DPAD_RIGHT -> state.dpad[1] = pressed
            KeyEvent.KEYCODE_DPAD_DOWN -> state.dpad[2] = pressed
            KeyEvent.KEYCODE_DPAD_LEFT -> state.dpad[3] = pressed
            // Triggers normally arrive as axes. A pad with digital triggers only sends the keys.
            KeyEvent.KEYCODE_BUTTON_L2 -> if (!hasAxis(device, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE))
                state.triggerL = if (pressed) 1f else 0f
            KeyEvent.KEYCODE_BUTTON_R2 -> if (!hasAxis(device, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS))
                state.triggerR = if (pressed) 1f else 0f
            else -> {
                val idx = buttonIndex(code)
                // Back, volume and the like stay Android's.
                if (idx < 0) return false
                state.setPressed(idx, pressed)
            }
        }
        publish(slot)
        return true
    }

    /** True when the event came from a pad and was consumed. */
    fun onMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        if (event.action != MotionEvent.ACTION_MOVE) return false
        val device = event.device ?: return false
        if (!isGameController(device)) return false
        val slot = slotFor(device) ?: return true
        val state = states[slot]
        state.thumbLX = centered(event, MotionEvent.AXIS_X)
        state.thumbLY = centered(event, MotionEvent.AXIS_Y)
        state.thumbRX = centered(event, MotionEvent.AXIS_Z)
        state.thumbRY = centered(event, MotionEvent.AXIS_RZ)
        if (hasAxis(device, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)) {
            state.triggerL = max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        }
        if (hasAxis(device, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)) {
            state.triggerR = max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        }
        // A hat d-pad; a pad whose d-pad is keys leaves these ranges absent and the keys stand.
        if (device.getMotionRange(MotionEvent.AXIS_HAT_X) != null) {
            val x = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val y = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            state.dpad[0] = y <= -0.5f
            state.dpad[1] = x >= 0.5f
            state.dpad[2] = y >= 0.5f
            state.dpad[3] = x <= -0.5f
        }
        publish(slot)
        return true
    }

    private fun publish(slot: Int) {
        writers[slot]?.writeGamepadState(states[slot])
    }

    /** One slot per physical pad: its sub-devices (a pad can enumerate several) share it. */
    private fun slotFor(device: InputDevice): Int? {
        val key = device.descriptor ?: "${device.name}:${device.vendorId}:${device.productId}"
        slotByDescriptor[key]?.let { return it }
        val used = slotByDescriptor.values.toSet()
        val slot = (0 until MAX_SLOTS).firstOrNull { it !in used } ?: return null
        slotByDescriptor[key] = slot
        states[slot].reset()
        writers[slot] = FakeInputWriter(fakeInputDir.path, slot).also { it.open() }
        Log.i(TAG, "slot $slot <- ${device.name} (${"%04x:%04x".format(device.vendorId, device.productId)})")
        return slot
    }

    private fun centered(event: MotionEvent, axis: Int): Float {
        val range = event.device?.getMotionRange(axis, event.source) ?: return 0f
        val value = event.getAxisValue(axis)
        if (abs(value) <= range.flat || abs(value) < STICK_DEAD_ZONE) return 0f
        return value
    }

    // ---- Rumble: libfakeinput.so connects to this abstract socket and sends one 8-byte record
    // per force-feedback play: strong, weak, duration ms, slot (little-endian u16 each).

    private fun startRumbleListener() {
        if (rumbleRunning) return
        rumbleRunning = true
        Thread({
            try {
                val server = LocalServerSocket(RUMBLE_SOCKET)
                rumbleServer = server
                while (rumbleRunning) {
                    val client = server.accept()
                    try {
                        val buf = ByteArray(8)
                        if (client.inputStream.read(buf) == 8) {
                            fun u16(i: Int) = (buf[i].toInt() and 0xff) or ((buf[i + 1].toInt() and 0xff) shl 8)
                            rumble(u16(0), u16(2), u16(4), u16(6))
                        }
                    } catch (e: IOException) {
                        Log.w(TAG, "rumble client: ${e.message}")
                    } finally {
                        try { client.close() } catch (_: IOException) {}
                    }
                }
            } catch (e: IOException) {
                if (rumbleRunning) Log.w(TAG, "rumble listener: ${e.message}")
            }
        }, "Rumble").apply { isDaemon = true }.start()
    }

    /** The pad's own motor when it has one, the handheld's otherwise. */
    @Suppress("DEPRECATION")
    private fun rumble(strong: Int, weak: Int, durationMs: Int, slot: Int) {
        val descriptor = slotByDescriptor.entries.firstOrNull { it.value == slot }?.key
        val device = InputDevice.getDeviceIds().asSequence().mapNotNull { InputDevice.getDevice(it) }
            .firstOrNull { it.descriptor == descriptor }
        val padVibrator = device?.vibrator?.takeIf { it.hasVibrator() }
        val vibrator = padVibrator ?: appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!vibrator.hasVibrator()) return
        val magnitude = max(strong, weak)
        if (magnitude <= 0) {
            vibrator.cancel()
            return
        }
        val duration = max(1, durationMs).toLong()
        val amplitude = (magnitude * 255L / 65535L).toInt().coerceIn(1, 255)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
        } else {
            vibrator.vibrate(duration)
        }
    }

    companion object {
        private const val TAG = "Controllers"
        const val MAX_SLOTS = 4
        /** Must match the name libfakeinput.so connects to. */
        private const val RUMBLE_SOCKET = "winlator_vibration"
        private const val STICK_DEAD_ZONE = 0.15f

        // GamepadState bit order; 0-9 are also FakeInputWriter's snapshot layout.
        const val IDX_BUTTON_A = 0
        const val IDX_BUTTON_B = 1
        const val IDX_BUTTON_X = 2
        const val IDX_BUTTON_Y = 3
        const val IDX_BUTTON_L1 = 4
        const val IDX_BUTTON_R1 = 5
        const val IDX_BUTTON_SELECT = 6
        const val IDX_BUTTON_START = 7
        const val IDX_BUTTON_L3 = 8
        const val IDX_BUTTON_R3 = 9
        /** The Guide/Home button: opens Steam's own menu. Past the triggers' bits 10-11. */
        @JvmField val IDX_BUTTON_MODE: Byte = 12

        private fun buttonIndex(keyCode: Int): Int = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> IDX_BUTTON_A
            KeyEvent.KEYCODE_BUTTON_B -> IDX_BUTTON_B
            KeyEvent.KEYCODE_BUTTON_X -> IDX_BUTTON_X
            KeyEvent.KEYCODE_BUTTON_Y -> IDX_BUTTON_Y
            KeyEvent.KEYCODE_BUTTON_L1 -> IDX_BUTTON_L1
            KeyEvent.KEYCODE_BUTTON_R1 -> IDX_BUTTON_R1
            KeyEvent.KEYCODE_BUTTON_SELECT -> IDX_BUTTON_SELECT
            KeyEvent.KEYCODE_BUTTON_START -> IDX_BUTTON_START
            KeyEvent.KEYCODE_BUTTON_THUMBL -> IDX_BUTTON_L3
            KeyEvent.KEYCODE_BUTTON_THUMBR -> IDX_BUTTON_R3
            KeyEvent.KEYCODE_BUTTON_MODE -> IDX_BUTTON_MODE.toInt()
            else -> -1
        }

        private fun hasAxis(device: InputDevice, vararg axes: Int) =
            axes.any { device.getMotionRange(it) != null }

        /**
         * A gamepad or joystick with real sticks or face buttons. Rejects fingerprint readers and
         * the keyboard-only aux boards some handhelds expose (Bannerlator's filter).
         */
        fun isGameController(device: InputDevice?): Boolean {
            if (device == null || device.isVirtual) return false
            val name = device.name?.lowercase().orEmpty()
            if ("uinput-fpc" in name || "goodix_fp" in name || "uinput-" in name) return false
            val sources = device.sources
            val gamepad = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
            val joystick = sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK &&
                sources and InputDevice.SOURCE_MOUSE == 0
            if (!gamepad && !joystick) return false
            val axis = listOf(
                MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
                MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y,
            ).any { device.getMotionRange(it) != null }
            val buttons = device.hasKeys(
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_START,
            ).any { it }
            return axis || buttons
        }
    }
}
