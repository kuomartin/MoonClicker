package com.xaxaxax.relc.input

import android.view.KeyEvent

enum class SimplePhysicalKey(val wireName: String, val keyCode: Int) {
    BACK("BACK", KeyEvent.KEYCODE_BACK),
    HOME("HOME", KeyEvent.KEYCODE_HOME),
    POWER("POWER", KeyEvent.KEYCODE_POWER),
    VOLUME_UP("VOLUME_UP", KeyEvent.KEYCODE_VOLUME_UP),
    VOLUME_DOWN("VOLUME_DOWN", KeyEvent.KEYCODE_VOLUME_DOWN),
    ;

    companion object {
        fun parse(s: String): SimplePhysicalKey {
            val t = s.trim()
            return entries.firstOrNull { it.wireName.equals(t, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown key: $s")
        }
    }
}
