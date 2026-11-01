package com.jdenglish.app.translate

import android.content.Context

/**
 * Tiered translator: glossary -> rules -> cache -> backend (+ post-edit).
 * Singleton per process; obtain with TranslationEngine.get(context).
 * OWNER: Engine. This file is a stub that must keep these public signatures.
 */
class TranslationEngine private constructor(private val context: Context) {

    /** Translate many strings (same order out). null = leave original. Never throws. */
    suspend fun translate(items: List<TextItem>): List<String?> = items.map { null }

    /** English -> Chinese for search queries. Returns null if impossible. */
    suspend fun toChinese(english: String): String? = null

    /** Kick off model download etc. Safe to call repeatedly. */
    fun warmUp() {}

    /** Drop the persistent cache (Settings -> Clear cache). */
    suspend fun clearCache() {}

    /** Human-readable status for Settings ("Offline model ready", "Downloading...", ...). */
    fun status(): String = ""

    companion object {
        @Volatile private var instance: TranslationEngine? = null
        fun get(context: Context): TranslationEngine =
            instance ?: synchronized(this) {
                instance ?: TranslationEngine(context.applicationContext).also { instance = it }
            }
    }
}
