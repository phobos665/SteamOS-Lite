package com.steamoslite.ui;

import android.view.KeyEvent;

/** Android key codes -> Linux evdev codes for wl_keyboard (Bannerlator's table). */
final class Keys {
    private Keys() {}

    /** The evdev code, or -1 when the key is not in the table. */
    public static int toEvdev(int kc) {
        switch (kc) {
            // Letters (evdev order is NOT alphabetical)
            case KeyEvent.KEYCODE_A: return 30; case KeyEvent.KEYCODE_B: return 48;
            case KeyEvent.KEYCODE_C: return 46; case KeyEvent.KEYCODE_D: return 32;
            case KeyEvent.KEYCODE_E: return 18; case KeyEvent.KEYCODE_F: return 33;
            case KeyEvent.KEYCODE_G: return 34; case KeyEvent.KEYCODE_H: return 35;
            case KeyEvent.KEYCODE_I: return 23; case KeyEvent.KEYCODE_J: return 36;
            case KeyEvent.KEYCODE_K: return 37; case KeyEvent.KEYCODE_L: return 38;
            case KeyEvent.KEYCODE_M: return 50; case KeyEvent.KEYCODE_N: return 49;
            case KeyEvent.KEYCODE_O: return 24; case KeyEvent.KEYCODE_P: return 25;
            case KeyEvent.KEYCODE_Q: return 16; case KeyEvent.KEYCODE_R: return 19;
            case KeyEvent.KEYCODE_S: return 31; case KeyEvent.KEYCODE_T: return 20;
            case KeyEvent.KEYCODE_U: return 22; case KeyEvent.KEYCODE_V: return 47;
            case KeyEvent.KEYCODE_W: return 17; case KeyEvent.KEYCODE_X: return 45;
            case KeyEvent.KEYCODE_Y: return 21; case KeyEvent.KEYCODE_Z: return 44;
            // Digit row
            case KeyEvent.KEYCODE_1: return 2;  case KeyEvent.KEYCODE_2: return 3;
            case KeyEvent.KEYCODE_3: return 4;  case KeyEvent.KEYCODE_4: return 5;
            case KeyEvent.KEYCODE_5: return 6;  case KeyEvent.KEYCODE_6: return 7;
            case KeyEvent.KEYCODE_7: return 8;  case KeyEvent.KEYCODE_8: return 9;
            case KeyEvent.KEYCODE_9: return 10; case KeyEvent.KEYCODE_0: return 11;
            // Whitespace / edit
            case KeyEvent.KEYCODE_ENTER: return 28; case KeyEvent.KEYCODE_NUMPAD_ENTER: return 28;
            case KeyEvent.KEYCODE_SPACE: return 57; case KeyEvent.KEYCODE_TAB: return 15;
            case KeyEvent.KEYCODE_DEL: return 14; /* backspace */
            case KeyEvent.KEYCODE_FORWARD_DEL: return 111; case KeyEvent.KEYCODE_ESCAPE: return 1;
            // Modifiers
            case KeyEvent.KEYCODE_SHIFT_LEFT: return 42; case KeyEvent.KEYCODE_SHIFT_RIGHT: return 54;
            case KeyEvent.KEYCODE_CTRL_LEFT: return 29; case KeyEvent.KEYCODE_CTRL_RIGHT: return 97;
            case KeyEvent.KEYCODE_ALT_LEFT: return 56; case KeyEvent.KEYCODE_ALT_RIGHT: return 100;
            case KeyEvent.KEYCODE_CAPS_LOCK: return 58;
            // Arrows / nav
            case KeyEvent.KEYCODE_DPAD_UP: return 103; case KeyEvent.KEYCODE_DPAD_DOWN: return 108;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 105; case KeyEvent.KEYCODE_DPAD_RIGHT: return 106;
            case KeyEvent.KEYCODE_MOVE_HOME: return 102; case KeyEvent.KEYCODE_MOVE_END: return 107;
            case KeyEvent.KEYCODE_PAGE_UP: return 104; case KeyEvent.KEYCODE_PAGE_DOWN: return 109;
            case KeyEvent.KEYCODE_INSERT: return 110;
            // Punctuation
            case KeyEvent.KEYCODE_GRAVE: return 41; case KeyEvent.KEYCODE_MINUS: return 12;
            case KeyEvent.KEYCODE_EQUALS: return 13; case KeyEvent.KEYCODE_LEFT_BRACKET: return 26;
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return 27; case KeyEvent.KEYCODE_BACKSLASH: return 43;
            case KeyEvent.KEYCODE_SEMICOLON: return 39; case KeyEvent.KEYCODE_APOSTROPHE: return 40;
            case KeyEvent.KEYCODE_SLASH: return 53; case KeyEvent.KEYCODE_COMMA: return 51;
            case KeyEvent.KEYCODE_PERIOD: return 52;
            // Function row
            case KeyEvent.KEYCODE_F1: return 59; case KeyEvent.KEYCODE_F2: return 60;
            case KeyEvent.KEYCODE_F3: return 61; case KeyEvent.KEYCODE_F4: return 62;
            case KeyEvent.KEYCODE_F5: return 63; case KeyEvent.KEYCODE_F6: return 64;
            case KeyEvent.KEYCODE_F7: return 65; case KeyEvent.KEYCODE_F8: return 66;
            case KeyEvent.KEYCODE_F9: return 67; case KeyEvent.KEYCODE_F10: return 68;
            case KeyEvent.KEYCODE_F11: return 87; case KeyEvent.KEYCODE_F12: return 88;
            default: return -1;
        }
    }
}
