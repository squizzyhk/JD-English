package com.jdenglish.app.translate

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/** Typed access to user preferences. Keys are also used by res/xml/preferences.xml. */
class Settings(context: Context) {
    val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    val images: Boolean get() = prefs.getBoolean(KEY_IMAGES, true)

    /** "mlkit" or "claude" */
    val engine: String get() = prefs.getString(KEY_ENGINE, "mlkit") ?: "mlkit"

    val claudeKey: String get() = prefs.getString(KEY_CLAUDE_KEY, "")?.trim().orEmpty()

    val claudeModel: String
        get() = prefs.getString(KEY_CLAUDE_MODEL, DEFAULT_CLAUDE_MODEL)?.trim()?.ifEmpty { null } ?: DEFAULT_CLAUDE_MODEL

    /** True when the Claude engine is selected and an API key is present. */
    val claudeReady: Boolean get() = engine == "claude" && claudeKey.isNotEmpty()

    /** Only translate images while on Wi-Fi/unmetered? (OCR runs locally; this only limits image re-downloads) */
    val imagesWifiOnly: Boolean get() = prefs.getBoolean(KEY_IMAGES_WIFI_ONLY, false)

    companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_IMAGES = "translate_images"
        const val KEY_ENGINE = "engine"
        const val KEY_CLAUDE_KEY = "claude_api_key"
        const val KEY_CLAUDE_MODEL = "claude_model"
        const val KEY_IMAGES_WIFI_ONLY = "images_wifi_only"
        const val DEFAULT_CLAUDE_MODEL = "claude-haiku-5-5"
    }
}
