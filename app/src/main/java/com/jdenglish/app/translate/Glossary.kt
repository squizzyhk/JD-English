package com.jdenglish.app.translate

import android.content.Context

/**
 * Exact-match lexicon loaded from assets/glossary/ (every .json file).
 * JSON format of each file: {"中文": "English", ...} (flat object; keys are normalized
 * by trimming and collapsing whitespace). Files are merged in alphabetical order; later wins.
 * OWNER: Engine (loader). Lexicon owner fills the JSON files.
 */
class Glossary(private val map: Map<String, String>) {
    fun lookup(zh: String): String? = map[normalize(zh)]
    val size: Int get() = map.size
    /** All entries (for reverse lookup / Claude hints). */
    fun entries(): Map<String, String> = map

    companion object {
        fun normalize(s: String): String = s.trim().replace(Regex("\\s+"), " ")
        fun load(context: Context): Glossary = Glossary(emptyMap()) // stub; Engine implements
    }
}
