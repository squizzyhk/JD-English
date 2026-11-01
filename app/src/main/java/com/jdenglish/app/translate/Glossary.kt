package com.jdenglish.app.translate

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Exact-match lexicon loaded from assets/glossary/ (every .json file).
 * JSON format of each file: {"中文": "English", ...} (flat object; keys are normalized
 * by trimming and collapsing whitespace). Files are merged in alphabetical order; later wins.
 * OWNER: Engine (loader). Lexicon owner fills the JSON files.
 *
 * Optional `assets/glossary/_protected.txt` (one Chinese term per line, `#` comments allowed)
 * lists JD-specific brand terms that are shielded from machine translation and substituted
 * with their glossary English afterwards.
 */
class Glossary(
    private val map: Map<String, String>,
    /** JD brand terms (zh) to shield from MT; each has a glossary entry. Longest first. */
    val protectedTerms: List<String> = emptyList(),
) {
    fun lookup(zh: String): String? = map[normalize(zh)]
    val size: Int get() = map.size
    /** All entries (for reverse lookup / Claude hints). */
    fun entries(): Map<String, String> = map

    /** Case-insensitive English -> Chinese exact lookup (built lazily). */
    fun reverseLookup(en: String): String? {
        val k = reverseKey(en)
        if (k.isEmpty()) return null
        return reverse[k]
    }

    private val reverse: Map<String, String> by lazy {
        val out = HashMap<String, String>(map.size * 2)
        // Prefer the shortest Chinese key for a given English value (usually the canonical term).
        for ((zh, en) in map) {
            val k = reverseKey(en)
            if (k.isEmpty()) continue
            val prev = out[k]
            if (prev == null || zh.length < prev.length) out[k] = zh
        }
        out
    }

    companion object {
        private const val TAG = "JDT.Glossary"
        private const val DIR = "glossary"
        private const val PROTECTED_FILE = "_protected.txt"
        private val WS = Regex("\\s+")

        fun normalize(s: String): String = s.trim().replace(WS, " ")

        private fun reverseKey(en: String): String =
            normalize(en).lowercase().trimEnd('.', '!', '?')

        @Volatile private var cached: Glossary? = null

        /** Loads (once per process) and caches the merged glossary. Never throws. */
        fun load(context: Context): Glossary {
            cached?.let { return it }
            synchronized(this) {
                cached?.let { return it }
                val g = try {
                    loadFromAssets(context.applicationContext)
                } catch (t: Throwable) {
                    Log.e(TAG, "Glossary load failed", t)
                    Glossary(emptyMap())
                }
                cached = g
                return g
            }
        }

        /** True once [load] has completed at least once. */
        val isLoaded: Boolean get() = cached != null

        private fun loadFromAssets(context: Context): Glossary {
            val am = context.assets
            val names = try {
                am.list(DIR)?.toList().orEmpty()
            } catch (t: Throwable) {
                Log.w(TAG, "No assets/$DIR folder", t)
                emptyList()
            }
            val jsonFiles = names.filter { it.endsWith(".json", ignoreCase = true) }.sorted()
            val texts = jsonFiles.mapNotNull { name ->
                try {
                    name to am.open("$DIR/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
                } catch (t: Throwable) {
                    Log.w(TAG, "Cannot read $DIR/$name", t); null
                }
            }
            val map = parseFiles(texts) { msg -> Log.w(TAG, msg) }
            val protectedRaw = if (PROTECTED_FILE in names) {
                try {
                    am.open("$DIR/$PROTECTED_FILE").bufferedReader(Charsets.UTF_8).use { it.readText() }
                } catch (t: Throwable) {
                    Log.w(TAG, "Cannot read $PROTECTED_FILE", t); null
                }
            } else null
            val prot = parseProtected(protectedRaw, map)
            Log.i(TAG, "Loaded ${map.size} glossary terms from ${texts.size} file(s); ${prot.size} protected")
            return Glossary(map, prot)
        }

        /**
         * Pure merge of glossary JSON files (already sorted by name). Malformed files and
         * non-string values are skipped and reported through [warn].
         */
        fun parseFiles(files: List<Pair<String, String>>, warn: (String) -> Unit = {}): Map<String, String> {
            val out = HashMap<String, String>()
            for ((name, text) in files) {
                val obj = try {
                    JSONObject(text.removePrefix("﻿"))
                } catch (t: Throwable) {
                    warn("Skipping malformed glossary file $name: ${t.message}"); continue
                }
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.opt(k)
                    if (v !is String) continue
                    val nk = normalize(k)
                    val nv = normalize(v)
                    if (nk.isEmpty() || nv.isEmpty()) continue
                    out[nk] = nv
                }
            }
            return out
        }

        /** Parses _protected.txt; keeps terms of length >= 2 that have a glossary entry. Longest first. */
        fun parseProtected(text: String?, map: Map<String, String>): List<String> {
            if (text.isNullOrBlank()) return emptyList()
            return text.lineSequence()
                .map { normalize(it.substringBefore('#')) }
                .filter { it.length >= 2 && Cjk.hasHan(it) && map.containsKey(it) }
                .distinct()
                .sortedByDescending { it.length }
                .toList()
        }
    }
}
