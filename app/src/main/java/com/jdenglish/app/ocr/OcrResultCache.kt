package com.jdenglish.app.ocr

import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two-level cache of image-translation results (the JSON returned to JS):
 * an in-memory LRU (instant when re-visiting pages) and small JSON files on disk
 * (survive restarts). Keys include the translation engine id, because the English
 * text depends on it. Thread-safe.
 */
internal class OcrResultCache(private val dir: File, private val memEntries: Int = 200, private val maxFiles: Int = 2000) {

    private val mem = object : LinkedHashMap<String, String>(memEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > memEntries
    }
    private val writesSinceTrim = AtomicInteger(TRIM_EVERY) // trim on first write after start

    fun key(url: String, engine: String): String = sha256("v$VERSION|$engine|$url")

    fun get(key: String): String? {
        synchronized(mem) { mem[key] }?.let { return it }
        val f = File(dir, "$key.json")
        return try {
            if (!f.isFile) return null
            val s = f.readText()
            if (s.isEmpty()) return null
            synchronized(mem) { mem[key] = s }
            // Touch so trimming keeps recently used results.
            f.setLastModified(System.currentTimeMillis())
            s
        } catch (e: Exception) {
            null
        }
    }

    fun put(key: String, json: String) {
        synchronized(mem) { mem[key] = json }
        try {
            if (!dir.isDirectory && !dir.mkdirs()) return
            val tmp = File(dir, "$key.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(File(dir, "$key.json"))) tmp.delete()
            if (writesSinceTrim.incrementAndGet() >= TRIM_EVERY) {
                writesSinceTrim.set(0)
                trim()
            }
        } catch (e: Exception) {
            Log.w(TAG, "disk cache write failed: ${e.message}")
        }
    }

    fun clear() {
        synchronized(mem) { mem.clear() }
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun trim() {
        val files = dir.listFiles() ?: return
        if (files.size <= maxFiles) return
        val target = (maxFiles * 0.9).toInt()
        files.sortedBy { it.lastModified() }.take(files.size - target).forEach { it.delete() }
    }

    companion object {
        private const val TAG = "JDT-OcrCache"
        /** Bump when the OCR/grouping output changes so stale results are ignored. */
        const val VERSION = 1
        private const val TRIM_EVERY = 50

        fun sha256(s: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(40)
            for (i in 0 until 20) sb.append(String.format(java.util.Locale.ROOT, "%02x", d[i].toInt() and 0xFF))
            return sb.toString()
        }
    }
}
