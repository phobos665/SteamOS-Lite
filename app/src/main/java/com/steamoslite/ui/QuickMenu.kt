package com.steamoslite.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The in-session quick menu the back button opens: a panel over SteamOS with a few actions, driven
 * by touch or the pad (D-pad/stick to move, A to choose, B or back to close).
 */
class QuickMenu(context: Context) : FrameLayout(context) {
    class Item(val label: () -> String, val action: () -> Unit)

    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(24), dp(20), dp(24))
        background = GradientDrawable().apply {
            setColor(0xF01B2530.toInt())
            cornerRadius = dp(12).toFloat()
        }
    }
    private var items: List<Item> = emptyList()
    private val rows = mutableListOf<TextView>()

    init {
        setBackgroundColor(0x99000000.toInt())
        visibility = GONE
        isClickable = true
        setOnClickListener { close() } // a tap outside the panel
        addView(panel, LayoutParams(dp(320), LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START).apply {
            marginStart = dp(24)
        })
        panel.isClickable = true // taps on the panel itself do not close the menu
    }

    val isOpen get() = visibility == VISIBLE

    fun setItems(title: String, items: List<Item>) {
        this.items = items
        panel.removeAllViews()
        rows.clear()
        panel.addView(TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, dp(8), dp(16))
        })
        for (item in items) {
            val row = TextView(context).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                background = rowBackground()
                setOnClickListener { close(); item.action() }
            }
            rows += row
            panel.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            })
        }
    }

    fun open() {
        rows.forEachIndexed { i, row -> row.text = items[i].label() }
        visibility = VISIBLE
        bringToFront()
        rows.firstOrNull()?.requestFocus()
    }

    fun close() {
        visibility = GONE
    }

    /**
     * Pad keys while the menu is open. Android does not treat the pad's A as a confirm key on every
     * version, so A clicks the focused row here; B closes. The D-pad goes to normal focus movement.
     */
    fun onPadKey(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (event.action == KeyEvent.ACTION_UP) rows.firstOrNull { it.isFocused }?.performClick()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (event.action == KeyEvent.ACTION_UP) close()
                return true
            }
        }
        return false
    }

    private fun rowBackground() = StateListDrawable().apply {
        val focused = GradientDrawable().apply { setColor(0xFF1A9FFF.toInt()); cornerRadius = dp(8).toFloat() }
        val idle = GradientDrawable().apply { setColor(0x22FFFFFF); cornerRadius = dp(8).toFloat() }
        addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(android.R.attr.state_pressed), focused)
        addState(intArrayOf(), idle)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
