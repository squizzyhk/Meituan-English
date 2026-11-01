package com.mtenglish.app.overlay

import android.content.Context

/** Overlay-only UI state (bubble position, labels on/off), kept apart from the user settings. */
internal class OverlayState(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var labelsOn: Boolean
        get() = sp.getBoolean(KEY_LABELS_ON, true)
        set(v) = sp.edit().putBoolean(KEY_LABELS_ON, v).apply()

    var collapsed: Boolean
        get() = sp.getBoolean(KEY_COLLAPSED, false)
        set(v) = sp.edit().putBoolean(KEY_COLLAPSED, v).apply()

    /** Bubble top-left in screen pixels; -1 means "not placed yet". */
    val bubbleX: Int get() = sp.getInt(KEY_X, -1)
    val bubbleY: Int get() = sp.getInt(KEY_Y, -1)

    fun saveBubble(x: Int, y: Int) {
        sp.edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }

    private companion object {
        const val FILE = "overlay_state"
        const val KEY_LABELS_ON = "labels_on"
        const val KEY_COLLAPSED = "bubble_collapsed"
        const val KEY_X = "bubble_x"
        const val KEY_Y = "bubble_y"
    }
}
