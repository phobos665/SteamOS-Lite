package com.steamoslite.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/**
 * A touch gamepad drawn over SteamOS, feeding the session's player-1 pad.
 *
 * Ported from GameNative's InputControlsView and ControlElement (GPL-3.0), gamepad bindings only:
 * the same profile format (its "Virtual Gamepad" layout is the one bundled), the same sizing
 * (everything scales from a grid of 1/100 of the view's width), the same look (outlines at 40%
 * opacity that turn blue while held) and the same stick and D-pad response. Keyboard and mouse
 * bindings, the editor, gyro and radial menus are left out.
 *
 * A touch that does not land on a control falls through to the screen underneath, so the touch
 * mouse still works around the controls.
 */
@SuppressLint("ViewConstructor")
class OnScreenController(context: Context, private val onState: (GamepadState) -> Unit) : View(context) {
    private enum class Type { BUTTON, D_PAD, STICK }
    private enum class Shape { CIRCLE, RECT, ROUND_RECT, SQUARE }

    private inner class Element(
        val type: Type,
        val shape: Shape,
        val bindings: List<String>,
        val scale: Float,
        val fx: Float,
        val fy: Float,
        val text: String,
        val iconId: Int,
    ) {
        var pointerId = -1
        val states = BooleanArray(4)
        var thumb: PointF? = null
        val box = Rect()

        fun layout(width: Int, height: Int, grid: Int) {
            val (hw, hh) = when (type) {
                Type.BUTTON -> when (shape) {
                    Shape.RECT, Shape.ROUND_RECT -> grid * 4f to grid * 2f
                    Shape.SQUARE -> grid * 2.5f to grid * 2.5f
                    Shape.CIRCLE -> grid * 3f to grid * 3f
                }
                Type.D_PAD -> grid * 7f to grid * 7f
                Type.STICK -> grid * 6f to grid * 6f
            }
            val x = (fx * width).toInt()
            val y = (fy * height).toInt()
            box.set(x - (hw * scale).toInt(), y - (hh * scale).toInt(), x + (hw * scale).toInt(), y + (hh * scale).toInt())
        }

        val label: String
            get() = text.ifEmpty { LABELS[bindings.first()] ?: "" }
    }

    private val elements = mutableListOf<Element>()
    private val state = GamepadState()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val icons = HashMap<Int, Bitmap?>()
    private var grid = 0

    init {
        loadProfile("inputcontrols/virtual-gamepad.icp")
    }

    private fun loadProfile(asset: String) {
        val json = JSONObject(context.assets.open(asset).bufferedReader().use { it.readText() })
        val list = json.getJSONArray("elements")
        for (i in 0 until list.length()) {
            val e = list.getJSONObject(i)
            val type = runCatching { Type.valueOf(e.getString("type")) }.getOrNull() ?: continue
            val b = e.getJSONArray("bindings")
            elements += Element(
                type = type,
                shape = runCatching { Shape.valueOf(e.optString("shape")) }.getOrDefault(Shape.CIRCLE),
                bindings = (0 until b.length()).map { b.getString(it) },
                scale = e.optDouble("scale", 1.0).toFloat(),
                fx = e.getDouble("x").toFloat(),
                fy = e.getDouble("y").toFloat(),
                text = e.optString("text", ""),
                iconId = e.optInt("iconId", 0),
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        grid = w / 100
        elements.forEach { it.layout(w, h, grid) }
    }

    /** Hidden or shown: everything held is let go, so no input sticks when the pad disappears. */
    fun releaseAll() {
        elements.forEach { it.pointerId = -1; it.states.fill(false); it.thumb = null }
        state.reset()
        onState(state)
        invalidate()
    }

    // ---- Drawing (GameNative's ControlElement.draw)

    override fun onDraw(canvas: Canvas) {
        if (grid == 0) return
        val stroke = grid * 0.25f
        for (e in elements) {
            val active = e.pointerId != -1
            val color = if (active) ACTIVE_COLOR else NORMAL_COLOR
            paint.color = color
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            val b = e.box
            when (e.type) {
                Type.BUTTON -> {
                    when (e.shape) {
                        Shape.CIRCLE -> canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), b.width() * 0.5f, paint)
                        Shape.RECT -> canvas.drawRect(b, paint)
                        Shape.ROUND_RECT -> (b.height() * 0.5f).let { r -> canvas.drawRoundRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), r, r, paint) }
                        Shape.SQUARE -> (grid * 0.75f * e.scale).let { r -> canvas.drawRoundRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), r, r, paint) }
                    }
                    val icon = if (e.iconId > 0) icon(e.iconId) else null
                    if (icon != null) {
                        paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
                        val margin = grid * (if (e.shape == Shape.CIRCLE || e.shape == Shape.SQUARE) 2f else 1f) * e.scale
                        val half = ((min(b.width(), b.height()) - margin) * 0.5f).toInt()
                        val cx = b.centerX()
                        val cy = b.centerY()
                        canvas.drawBitmap(icon, null, Rect(cx - half, cy - half, cx + half, cy + half), paint)
                        paint.colorFilter = null
                    } else {
                        paint.style = Paint.Style.FILL
                        paint.textAlign = Paint.Align.CENTER
                        paint.textSize = min(textSizeFor(e.label, b.width() - stroke * 2), grid * 2 * e.scale)
                        canvas.drawText(e.label, b.exactCenterX(), b.exactCenterY() - (paint.descent() + paint.ascent()) * 0.5f, paint)
                    }
                }
                Type.D_PAD -> {
                    val cx = b.exactCenterX()
                    val cy = b.exactCenterY()
                    val offX = grid * 2 * e.scale
                    val offY = grid * 3 * e.scale
                    val start = grid * e.scale
                    for (i in 0 until 4) {
                        dpadPath(i, b, cx, cy, offX, offY, start)
                        paint.color = if (e.states[i]) ACTIVE_COLOR else NORMAL_COLOR
                        canvas.drawPath(path, paint)
                    }
                }
                Type.STICK -> {
                    val cx = b.exactCenterX()
                    val cy = b.exactCenterY()
                    canvas.drawCircle(cx, cy, b.height() * 0.5f, paint)
                    val tx = e.thumb?.x ?: cx
                    val ty = e.thumb?.y ?: cy
                    val thumbRadius = grid * 3.5f * e.scale
                    paint.style = Paint.Style.FILL
                    paint.color = Color.argb(min(50, Color.alpha(color)), Color.red(color), Color.green(color), Color.blue(color))
                    canvas.drawCircle(tx, ty, thumbRadius, paint)
                    paint.style = Paint.Style.STROKE
                    paint.color = color
                    canvas.drawCircle(tx, ty, thumbRadius + stroke * 0.5f, paint)
                }
            }
        }
    }

    private fun dpadPath(direction: Int, b: Rect, cx: Float, cy: Float, offX: Float, offY: Float, start: Float) {
        path.reset()
        when (direction) {
            0 -> { path.moveTo(cx, cy - start); path.lineTo(cx - offX, cy - offY); path.lineTo(cx - offX, b.top.toFloat()); path.lineTo(cx + offX, b.top.toFloat()); path.lineTo(cx + offX, cy - offY) }
            1 -> { path.moveTo(cx + start, cy); path.lineTo(cx + offY, cy - offX); path.lineTo(b.right.toFloat(), cy - offX); path.lineTo(b.right.toFloat(), cy + offX); path.lineTo(cx + offY, cy + offX) }
            2 -> { path.moveTo(cx, cy + start); path.lineTo(cx - offX, cy + offY); path.lineTo(cx - offX, b.bottom.toFloat()); path.lineTo(cx + offX, b.bottom.toFloat()); path.lineTo(cx + offX, cy + offY) }
            3 -> { path.moveTo(cx - start, cy); path.lineTo(cx - offY, cy - offX); path.lineTo(b.left.toFloat(), cy - offX); path.lineTo(b.left.toFloat(), cy + offX); path.lineTo(cx - offY, cy + offX) }
        }
        path.close()
    }

    private fun textSizeFor(text: String, width: Float): Float {
        paint.textSize = 48f
        val measured = paint.measureText(text)
        return if (measured <= 0f) 48f else 48f * width / measured
    }

    private fun icon(id: Int): Bitmap? = icons.getOrPut(id) {
        runCatching { context.assets.open("inputcontrols/icons/$id.png").use(BitmapFactory::decodeStream) }.getOrNull()
    }

    // ---- Touch (GameNative's handleTouchDown / handleTouchMove / handleTouchUp)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val id = event.getPointerId(index)
                val x = event.getX(index)
                val y = event.getY(index)
                val hit = elements.firstOrNull { it.pointerId == -1 && it.box.contains((x + 0.5f).toInt(), (y + 0.5f).toInt()) }
                // The first finger decides who owns the gesture: off the controls, it is the
                // screen's (the touch mouse), and the view underneath receives it.
                if (hit == null) return event.actionMasked != MotionEvent.ACTION_DOWN
                hit.pointerId = id
                when (hit.type) {
                    Type.BUTTON -> press(hit.bindings.first(), true)
                    else -> move(hit, x, y)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    elements.firstOrNull { it.pointerId == id && it.type != Type.BUTTON }?.let { move(it, event.getX(i), event.getY(i)) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(index)
                elements.firstOrNull { it.pointerId == id }?.let(::release)
            }
            MotionEvent.ACTION_CANCEL -> elements.filter { it.pointerId != -1 }.forEach(::release)
        }
        onState(state)
        invalidate()
        return true
    }

    private fun release(e: Element) {
        e.pointerId = -1
        when (e.type) {
            Type.BUTTON -> press(e.bindings.first(), false)
            Type.D_PAD -> for (i in 0 until 4) if (e.states[i]) { e.states[i] = false; press(e.bindings[i], false) }
            Type.STICK -> {
                e.thumb = null
                e.states.fill(false)
                for (i in 0 until 4) axis(e.bindings[i], 0f)
            }
        }
    }

    private fun move(e: Element, x: Float, y: Float) {
        val b = e.box
        val radius = b.width() * 0.5f
        var ox = x - b.left - radius
        var oy = y - b.top - radius
        if (ox * ox + oy * oy > radius * radius) {
            val angle = atan2(oy, ox)
            ox = cos(angle) * radius
            oy = sin(angle) * radius
        }
        val dx = (ox / radius).coerceIn(-1f, 1f)
        val dy = (oy / radius).coerceIn(-1f, 1f)
        if (e.type == Type.STICK) {
            e.thumb = PointF(b.left + dx * radius + radius, b.top + dy * radius + radius)
            for (i in 0 until 4) {
                val v = if (i == 1 || i == 3) dx else dy
                axis(e.bindings[i], (maxOf(0f, abs(v) - 0.01f) * sign(v) * STICK_SENSITIVITY).coerceIn(-1f, 1f))
            }
        } else {
            val next = booleanArrayOf(dy <= -DPAD_DEAD_ZONE, dx >= DPAD_DEAD_ZONE, dy >= DPAD_DEAD_ZONE, dx <= -DPAD_DEAD_ZONE)
            for (i in 0 until 4) {
                if (e.states[i] != next[i]) press(e.bindings[i], next[i])
                e.states[i] = next[i]
            }
        }
    }

    /** A button or D-pad binding. */
    private fun press(binding: String, down: Boolean) {
        when (binding) {
            "GAMEPAD_BUTTON_L2" -> state.triggerL = if (down) 1f else 0f
            "GAMEPAD_BUTTON_R2" -> state.triggerR = if (down) 1f else 0f
            "GAMEPAD_DPAD_UP" -> state.dpad[0] = down
            "GAMEPAD_DPAD_RIGHT" -> state.dpad[1] = down
            "GAMEPAD_DPAD_DOWN" -> state.dpad[2] = down
            "GAMEPAD_DPAD_LEFT" -> state.dpad[3] = down
            else -> BUTTONS[binding]?.let { state.setPressed(it, down) }
        }
    }

    /** A stick binding; [value] is signed along the direction's own axis (up/left negative). */
    private fun axis(binding: String, value: Float) {
        when (binding) {
            "GAMEPAD_LEFT_THUMB_UP", "GAMEPAD_LEFT_THUMB_DOWN" -> state.thumbLY = value
            "GAMEPAD_LEFT_THUMB_LEFT", "GAMEPAD_LEFT_THUMB_RIGHT" -> state.thumbLX = value
            "GAMEPAD_RIGHT_THUMB_UP", "GAMEPAD_RIGHT_THUMB_DOWN" -> state.thumbRY = value
            "GAMEPAD_RIGHT_THUMB_LEFT", "GAMEPAD_RIGHT_THUMB_RIGHT" -> state.thumbRX = value
        }
    }

    companion object {
        /** GameNative's DEFAULT_OVERLAY_OPACITY, its primary (white) and secondary (blue) colours. */
        private const val OPACITY = 0.4f
        private val NORMAL_COLOR = Color.argb((OPACITY * 255).toInt(), 255, 255, 255)
        private val ACTIVE_COLOR = Color.argb((OPACITY * 255).toInt(), 2, 119, 189)
        private const val STICK_SENSITIVITY = 3.0f
        private const val DPAD_DEAD_ZONE = 0.3f

        private val BUTTONS = mapOf(
            "GAMEPAD_BUTTON_A" to Controllers.IDX_BUTTON_A,
            "GAMEPAD_BUTTON_B" to Controllers.IDX_BUTTON_B,
            "GAMEPAD_BUTTON_X" to Controllers.IDX_BUTTON_X,
            "GAMEPAD_BUTTON_Y" to Controllers.IDX_BUTTON_Y,
            "GAMEPAD_BUTTON_L1" to Controllers.IDX_BUTTON_L1,
            "GAMEPAD_BUTTON_R1" to Controllers.IDX_BUTTON_R1,
            "GAMEPAD_BUTTON_SELECT" to Controllers.IDX_BUTTON_SELECT,
            "GAMEPAD_BUTTON_START" to Controllers.IDX_BUTTON_START,
            "GAMEPAD_BUTTON_L3" to Controllers.IDX_BUTTON_L3,
            "GAMEPAD_BUTTON_R3" to Controllers.IDX_BUTTON_R3,
            "GAMEPAD_BUTTON_MODE" to Controllers.IDX_BUTTON_MODE.toInt(),
        )

        /** GameNative's labels: the binding's name without "BUTTON ". */
        private val LABELS = mapOf(
            "GAMEPAD_BUTTON_A" to "A", "GAMEPAD_BUTTON_B" to "B",
            "GAMEPAD_BUTTON_X" to "X", "GAMEPAD_BUTTON_Y" to "Y",
            "GAMEPAD_BUTTON_L1" to "LB", "GAMEPAD_BUTTON_R1" to "RB",
            "GAMEPAD_BUTTON_L2" to "LT", "GAMEPAD_BUTTON_R2" to "RT",
            "GAMEPAD_BUTTON_L3" to "L3", "GAMEPAD_BUTTON_R3" to "R3",
            "GAMEPAD_BUTTON_START" to "START", "GAMEPAD_BUTTON_SELECT" to "SELECT",
        )
    }
}
