package com.rustvnt.remote

import android.view.KeyEvent

object Keymap {
    data class Scan(val code: Int, val extended: Boolean = false)
    private val map = mutableMapOf<Int, Scan>().apply {
        val letters = mapOf('Q' to 0x10, 'W' to 0x11, 'E' to 0x12, 'R' to 0x13, 'T' to 0x14, 'Y' to 0x15, 'U' to 0x16, 'I' to 0x17, 'O' to 0x18, 'P' to 0x19,
            'A' to 0x1e, 'S' to 0x1f, 'D' to 0x20, 'F' to 0x21, 'G' to 0x22, 'H' to 0x23, 'J' to 0x24, 'K' to 0x25, 'L' to 0x26,
            'Z' to 0x2c, 'X' to 0x2d, 'C' to 0x2e, 'V' to 0x2f, 'B' to 0x30, 'N' to 0x31, 'M' to 0x32)
        letters.forEach { (letter, scan) -> put(KeyEvent.KEYCODE_A + letter.code - 'A'.code, Scan(scan)) }
        for (i in 1..9) put(KeyEvent.KEYCODE_0 + i, Scan(i + 1))
        put(KeyEvent.KEYCODE_0, Scan(0x0b))
        val plain = mapOf(KeyEvent.KEYCODE_ESCAPE to 0x01, KeyEvent.KEYCODE_MINUS to 0x0c, KeyEvent.KEYCODE_EQUALS to 0x0d,
            KeyEvent.KEYCODE_DEL to 0x0e, KeyEvent.KEYCODE_TAB to 0x0f, KeyEvent.KEYCODE_LEFT_BRACKET to 0x1a,
            KeyEvent.KEYCODE_RIGHT_BRACKET to 0x1b, KeyEvent.KEYCODE_ENTER to 0x1c, KeyEvent.KEYCODE_CTRL_LEFT to 0x1d,
            KeyEvent.KEYCODE_SEMICOLON to 0x27, KeyEvent.KEYCODE_APOSTROPHE to 0x28, KeyEvent.KEYCODE_GRAVE to 0x29,
            KeyEvent.KEYCODE_SHIFT_LEFT to 0x2a, KeyEvent.KEYCODE_BACKSLASH to 0x2b, KeyEvent.KEYCODE_COMMA to 0x33,
            KeyEvent.KEYCODE_PERIOD to 0x34, KeyEvent.KEYCODE_SLASH to 0x35, KeyEvent.KEYCODE_SHIFT_RIGHT to 0x36,
            KeyEvent.KEYCODE_ALT_LEFT to 0x38, KeyEvent.KEYCODE_SPACE to 0x39, KeyEvent.KEYCODE_CAPS_LOCK to 0x3a,
            KeyEvent.KEYCODE_NUM_LOCK to 0x45, KeyEvent.KEYCODE_SCROLL_LOCK to 0x46)
        plain.forEach { (key, scan) -> put(key, Scan(scan)) }
        for (i in 0..9) put(KeyEvent.KEYCODE_F1 + i, Scan(0x3b + i))
        put(KeyEvent.KEYCODE_F11, Scan(0x57)); put(KeyEvent.KEYCODE_F12, Scan(0x58))
        val extended = mapOf(KeyEvent.KEYCODE_CTRL_RIGHT to 0x1d, KeyEvent.KEYCODE_ALT_RIGHT to 0x38,
            KeyEvent.KEYCODE_META_LEFT to 0x5b, KeyEvent.KEYCODE_META_RIGHT to 0x5c,
            KeyEvent.KEYCODE_DPAD_UP to 0x48, KeyEvent.KEYCODE_DPAD_DOWN to 0x50,
            KeyEvent.KEYCODE_DPAD_LEFT to 0x4b, KeyEvent.KEYCODE_DPAD_RIGHT to 0x4d,
            KeyEvent.KEYCODE_MOVE_HOME to 0x47, KeyEvent.KEYCODE_MOVE_END to 0x4f,
            KeyEvent.KEYCODE_PAGE_UP to 0x49, KeyEvent.KEYCODE_PAGE_DOWN to 0x51,
            KeyEvent.KEYCODE_INSERT to 0x52, KeyEvent.KEYCODE_FORWARD_DEL to 0x53,
            KeyEvent.KEYCODE_NUMPAD_ENTER to 0x1c, KeyEvent.KEYCODE_NUMPAD_DIVIDE to 0x35,
            KeyEvent.KEYCODE_SYSRQ to 0x37, KeyEvent.KEYCODE_MENU to 0x5d)
        extended.forEach { (key, scan) -> put(key, Scan(scan, true)) }
        val numpad = intArrayOf(0x52, 0x4f, 0x50, 0x51, 0x4b, 0x4c, 0x4d, 0x47, 0x48, 0x49)
        numpad.forEachIndexed { i, scan -> put(KeyEvent.KEYCODE_NUMPAD_0 + i, Scan(scan)) }
        put(KeyEvent.KEYCODE_NUMPAD_DOT, Scan(0x53)); put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, Scan(0x37))
        put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, Scan(0x4a)); put(KeyEvent.KEYCODE_NUMPAD_ADD, Scan(0x4e))
    }
    fun scan(keycode: Int): Scan? = map[keycode]
}
