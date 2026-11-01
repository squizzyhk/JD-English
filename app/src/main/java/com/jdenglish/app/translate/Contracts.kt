package com.jdenglish.app.translate

/**
 * Shared contracts. Do not change signatures without updating every owner
 * listed in docs/ARCHITECTURE.md.
 */

/** A string to translate plus a context hint: ui | title | product | attr | review | image */
data class TextItem(val text: String, val ctx: String = "ui")

/** A machine-translation backend (ML Kit, Claude, ...). Must be safe to call from a background thread. */
interface TranslationBackend {
    val id: String
    /** Returns translations in the same order; null where it failed. Suspends while working. */
    suspend fun translate(items: List<TextItem>): List<String?>
    /** Optional English -> Chinese (used for native search box). */
    suspend fun translateToChinese(text: String): String? = null
}

object Cjk {
    private val CJK = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF\\u3000-\\u303F\\uFF01-\\uFF60]")
    private val HAN = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]")
    fun hasHan(s: String) = HAN.containsMatchIn(s)
    fun hasCjk(s: String) = CJK.containsMatchIn(s)
}
