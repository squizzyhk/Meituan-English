package com.mtenglish.app

import android.content.Context
import androidx.preference.PreferenceManager

/** Typed access to user settings (backed by default SharedPreferences, edited in SettingsActivity). */
class Prefs(context: Context) {
    private val sp = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    var claudeApiKey: String
        get() = sp.getString(KEY_API_KEY, "")!!.trim()
        set(v) = sp.edit().putString(KEY_API_KEY, v.trim()).apply()

    var claudeModel: String
        get() = sp.getString(KEY_MODEL, DEFAULT_MODEL)!!
        set(v) = sp.edit().putString(KEY_MODEL, v).apply()

    /** API base URL; lets users route through their own proxy where api.anthropic.com is not reachable. */
    var claudeBaseUrl: String
        get() = sp.getString(KEY_BASE_URL, DEFAULT_BASE_URL)!!.trim().ifEmpty { DEFAULT_BASE_URL }
        set(v) = sp.edit().putString(KEY_BASE_URL, v.trim()).apply()

    var translateImages: Boolean
        get() = sp.getBoolean(KEY_IMAGES, true)
        set(v) = sp.edit().putBoolean(KEY_IMAGES, v).apply()

    var showOriginalOnLongPress: Boolean
        get() = sp.getBoolean(KEY_SHOW_ORIGINAL, true)
        set(v) = sp.edit().putBoolean(KEY_SHOW_ORIGINAL, v).apply()

    var offlineFallback: Boolean
        get() = sp.getBoolean(KEY_OFFLINE, true)
        set(v) = sp.edit().putBoolean(KEY_OFFLINE, v).apply()

    var overlayEnabled: Boolean
        get() = sp.getBoolean(KEY_OVERLAY, true)
        set(v) = sp.edit().putBoolean(KEY_OVERLAY, v).apply()

    companion object {
        const val KEY_API_KEY = "claude_api_key"
        const val KEY_MODEL = "claude_model"
        const val KEY_BASE_URL = "claude_base_url"
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        const val KEY_IMAGES = "translate_images"
        const val KEY_SHOW_ORIGINAL = "show_original_long_press"
        const val KEY_OFFLINE = "offline_fallback"
        const val KEY_OVERLAY = "overlay_enabled"
        const val DEFAULT_MODEL = "claude-opus-5-5"
    }
}
