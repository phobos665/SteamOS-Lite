package com.steamoslite.ui

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.steamoslite.wayland.WaylandCompositor

/**
 * The Android keyboard, typing into SteamOS.
 *
 * Steam runs under Xwayland inside gamescope, so nothing in the session asks our compositor for
 * text input: the keyboard's text has to arrive as key presses. This view holds the keyboard's
 * InputConnection and turns each character it produces into the evdev key (plus Shift) that makes
 * it on a US layout - the keymap the compositor hands its clients. Characters that layout has no
 * key for are dropped.
 *
 * Composing text (the word a keyboard shows underlined before committing it) is typed as it
 * changes, so what is on screen matches what is in the text box at every point.
 */
class KeyboardBridge(private val activity: Activity) : View(activity) {
    private val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    val keyboardVisible: Boolean
        get() = ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    fun show() {
        requestFocus()
        imm.showSoftInput(this, InputMethodManager.SHOW_FORCED)
    }

    fun hide() {
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Visible-password: keyboards then send each character as it is typed, with no suggestions
        // or autocorrect rewriting words that are already in the text box.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return Connection()
    }

    private inner class Connection : BaseInputConnection(this@KeyboardBridge, false) {
        /** What of the current composing text has already been typed. */
        private var typed = ""

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            retype(text?.toString().orEmpty())
            typed = ""
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            retype(text?.toString().orEmpty())
            return true
        }

        override fun finishComposingText(): Boolean {
            typed = ""
            return true
        }

        override fun setComposingRegion(start: Int, end: Int) = true

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) { tap(KEY_BACKSPACE) }
            repeat(afterLength) { tap(KEY_DELETE) }
            return true
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) =
            deleteSurroundingText(beforeLength, afterLength)

        // Keys the keyboard sends as keys (Backspace, Enter, digits on some layouts) take the same
        // route as a hardware keyboard's.
        override fun sendKeyEvent(event: KeyEvent) = activity.dispatchKeyEvent(event)

        override fun performEditorAction(actionCode: Int): Boolean {
            tap(KEY_ENTER)
            return true
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
        override fun getSelectedText(flags: Int): CharSequence? = null

        /** Brings the typed composing text to [text]: backspace over what differs, type the rest. */
        private fun retype(text: String) {
            val common = typed.commonPrefixWith(text).length
            repeat(typed.length - common) { tap(KEY_BACKSPACE) }
            text.substring(common).forEach(::typeChar)
            typed = text
        }
    }

    private fun typeChar(c: Char) {
        if (c == '\n') return tap(KEY_ENTER)
        if (c == '\t') return tap(KEY_TAB)
        val (code, shift) = keyFor(c) ?: return
        if (shift) WaylandCompositor.nativeSendKey(KEY_LEFTSHIFT, 1)
        tap(code)
        if (shift) WaylandCompositor.nativeSendKey(KEY_LEFTSHIFT, 0)
    }

    private fun tap(code: Int) {
        WaylandCompositor.nativeSendKey(code, 1)
        WaylandCompositor.nativeSendKey(code, 0)
    }

    companion object {
        private const val KEY_LEFTSHIFT = 42
        private const val KEY_BACKSPACE = 14
        private const val KEY_DELETE = 111
        private const val KEY_ENTER = 28
        private const val KEY_TAB = 15

        private const val SHIFTED = "!@#$%^&*()_+{}|:\"<>?~"
        private const val PLAIN = "1234567890-=[]\\;',./`"

        /** The evdev key and whether Shift is held for [c] on a US layout, or null. */
        fun keyFor(c: Char): Pair<Int, Boolean>? {
            val shifted = c.isUpperCase() || c in SHIFTED
            val base = when {
                c in 'A'..'Z' -> c.lowercaseChar()
                c in SHIFTED -> PLAIN[SHIFTED.indexOf(c)]
                else -> c
            }
            val keycode = when (base) {
                in 'a'..'z' -> KeyEvent.KEYCODE_A + (base - 'a')
                in '0'..'9' -> KeyEvent.KEYCODE_0 + (base - '0')
                ' ' -> KeyEvent.KEYCODE_SPACE
                '-' -> KeyEvent.KEYCODE_MINUS
                '=' -> KeyEvent.KEYCODE_EQUALS
                '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
                ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
                '\\' -> KeyEvent.KEYCODE_BACKSLASH
                ';' -> KeyEvent.KEYCODE_SEMICOLON
                '\'' -> KeyEvent.KEYCODE_APOSTROPHE
                ',' -> KeyEvent.KEYCODE_COMMA
                '.' -> KeyEvent.KEYCODE_PERIOD
                '/' -> KeyEvent.KEYCODE_SLASH
                '`' -> KeyEvent.KEYCODE_GRAVE
                else -> return null
            }
            val evdev = Keys.toEvdev(keycode)
            return if (evdev > 0) evdev to shifted else null
        }
    }
}
