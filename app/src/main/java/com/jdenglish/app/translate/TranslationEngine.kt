package com.jdenglish.app.translate

import android.content.Context
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tiered translator: glossary -> rules -> memory LRU -> SQLite cache -> backend (+ post-edit).
 * Singleton per process; obtain with TranslationEngine.get(context).
 * OWNER: Engine.
 */
class TranslationEngine private constructor(private val context: Context) {

    private val settings = Settings(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val warmedUp = AtomicBoolean(false)

    @Volatile private var glossary: Glossary? = null
    @Volatile private var rules: RuleEngine? = null

    val mlKit = MlKitBackend()
    val claude = ClaudeBackend(
        apiKey = { settings.claudeKey },
        model = { settings.claudeModel },
        glossary = { glossary },
    )
    private val cache: TranslationCache by lazy { TranslationCache(context) }
    private val memory = LruCache<String, String>(MEMORY_ENTRIES)

    /** Translate many strings (same order out). null = leave original. Never throws (except cancellation). */
    suspend fun translate(items: List<TextItem>): List<String?> {
        if (items.isEmpty()) return emptyList()
        return try {
            translateImpl(items)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "translate failed", t)
            items.map { null }
        }
    }

    private class Pending(val norm: String, val ctx: String) {
        val indices = ArrayList<Int>(1)
        var result: String? = null
    }

    private suspend fun translateImpl(items: List<TextItem>): List<String?> {
        val g = glossary()
        val out = arrayOfNulls<String>(items.size)
        val pending = LinkedHashMap<String, Pending>()

        // Tier 1-2: glossary exact, rules, and segment composition (all local and instant).
        items.forEachIndexed { i, item ->
            val raw = item.text
            if (!Cjk.hasHan(raw)) return@forEachIndexed
            val norm = Glossary.normalize(raw)
            val local = resolveLocal(norm, g) ?: EngineLogic.composeSegments(norm) { resolveLocal(it, g) }
            if (local != null) {
                out[i] = EngineLogic.keepOuterSpace(raw, local)
            } else {
                pending.getOrPut(norm) { Pending(norm, item.ctx) }.indices.add(i)
            }
        }
        if (pending.isEmpty()) return out.toList()

        val useClaude = settings.engine == ClaudeBackend.ID && claude.hasKey
        var unresolved: List<Pending> = pending.values.toList()
        if (useClaude) {
            unresolved = runTier(claude.id, unresolved) { batch ->
                claude.translate(batch.map { TextItem(it.norm, it.ctx) })
            }
        }
        if (unresolved.isNotEmpty()) {
            // Default engine, or fallback when Claude failed / has no key.
            unresolved = runTier(mlKit.id, unresolved) { batch -> translateWithMlKit(batch, g) }
        }

        for (p in pending.values) {
            val v = p.result ?: continue
            for (i in p.indices) {
                val item = items[i]
                out[i] = EngineLogic.keepOuterSpace(item.text, PostEditor.finish(v, item.ctx, item.text))
            }
        }
        return out.toList()
    }

    /** Memory -> SQLite -> backend for one engine id. Returns the entries still unresolved. */
    private suspend fun runTier(
        engineId: String,
        input: List<Pending>,
        backend: suspend (List<Pending>) -> List<String?>,
    ): List<Pending> {
        var misses = ArrayList<Pending>()
        for (p in input) {
            val hit = memory.get(memKey(engineId, p.norm))
            if (hit != null) p.result = hit else misses.add(p)
        }
        if (misses.isEmpty()) return emptyList()

        val dbHits = cache.getAll(engineId, misses.map { it.norm })
        if (dbHits.isNotEmpty()) {
            val still = ArrayList<Pending>()
            for (p in misses) {
                val hit = dbHits[p.norm]
                if (hit != null) {
                    p.result = hit
                    memory.put(memKey(engineId, p.norm), hit)
                } else still.add(p)
            }
            misses = still
        }
        if (misses.isEmpty()) return emptyList()

        val translated = try {
            backend(misses)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "$engineId backend failed", t)
            misses.map { null }
        }
        val toStore = HashMap<String, String>()
        val failed = ArrayList<Pending>()
        misses.forEachIndexed { k, p ->
            val raw = translated.getOrNull(k)
            val cleaned = raw?.let { PostEditor.clean(it) }?.takeIf { EngineLogic.isUsable(p.norm, it) }
            if (cleaned != null) {
                p.result = cleaned
                memory.put(memKey(engineId, p.norm), cleaned)
                toStore[p.norm] = cleaned
            } else failed.add(p)
        }
        if (toStore.isNotEmpty()) cache.putAll(engineId, toStore)
        return failed
    }

    /** ML Kit with JD brand terms shielded behind placeholders and restored from the glossary. */
    private suspend fun translateWithMlKit(batch: List<Pending>, g: Glossary): List<String?> {
        val terms = g.protectedTerms
        val prepared = batch.map { p ->
            if (terms.isEmpty()) null else EngineLogic.protect(p.norm, terms) { g.lookup(it) }
        }
        val inputs = batch.mapIndexed { k, p -> TextItem(prepared[k]?.text ?: p.norm, p.ctx) }
        val raw = mlKit.translate(inputs)
        val result = raw.toMutableList()
        val retry = ArrayList<Int>()
        prepared.forEachIndexed { k, prot ->
            if (prot == null) return@forEachIndexed
            val restored = raw[k]?.let { EngineLogic.restore(it, prot) }
            if (restored == null && raw[k] != null) retry.add(k) else result[k] = restored
        }
        if (retry.isNotEmpty()) {
            // The MT mangled a placeholder: translate those strings again without protection.
            val again = mlKit.translate(retry.map { TextItem(batch[it].norm, batch[it].ctx) })
            retry.forEachIndexed { j, k -> result[k] = again[j] }
        }
        return result
    }

    private fun resolveLocal(norm: String, g: Glossary): String? {
        g.lookup(norm)?.let { return it }
        val r = rulesFor(g) ?: return null
        return try {
            r.apply(norm)?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            Log.w(TAG, "rule engine error on \"$norm\"", t)
            null
        }
    }

    private fun rulesFor(g: Glossary): RuleEngine? {
        rules?.let { return it }
        return try {
            RuleEngine { g.lookup(it) }.also { rules = it }
        } catch (t: Throwable) {
            Log.e(TAG, "RuleEngine init failed", t); null
        }
    }

    private suspend fun glossary(): Glossary {
        glossary?.let { return it }
        return withContext(Dispatchers.IO) { Glossary.load(context).also { glossary = it } }
    }

    /** English -> Chinese for search queries. Returns null if impossible. */
    suspend fun toChinese(english: String): String? {
        val q = Glossary.normalize(english)
        if (q.isEmpty()) return null
        if (Cjk.hasHan(q)) return q
        return try {
            glossary().reverseLookup(q)?.let { return it }
            val viaClaude = if (settings.engine == ClaudeBackend.ID && claude.hasKey) claude.translateToChinese(q) else null
            viaClaude ?: mlKit.translateToChinese(q)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "toChinese failed", t)
            null
        }
    }

    /** Kick off glossary load + model download in the background. Safe to call repeatedly. */
    fun warmUp() {
        if (!warmedUp.compareAndSet(false, true)) return
        scope.launch {
            try {
                glossary()
                mlKit.refreshDownloadedState()
                mlKit.ensureReady()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "warmUp failed", t)
            }
        }
    }

    /** Drop the persistent cache (Settings -> Clear cache). */
    suspend fun clearCache() {
        memory.evictAll()
        try { cache.clear() } catch (t: Throwable) { Log.w(TAG, "clearCache failed", t) }
    }

    /** Human-readable status for Settings, e.g. "Offline model ready · 2,431 glossary terms · Engine: On-device". */
    fun status(): String {
        val parts = ArrayList<String>(4)
        parts.add(mlKit.statusText())
        val g = glossary
        parts.add(
            if (g == null) "Loading glossary…"
            else "${NumberFormat.getIntegerInstance(Locale.US).format(g.size)} glossary terms"
        )
        if (settings.engine == ClaudeBackend.ID) {
            parts.add("Engine: Claude (${settings.claudeModel})")
            when {
                !claude.hasKey -> parts.add("Add a Claude API key to use Claude; using on-device until then")
                claude.lastError != null -> parts.add("${claude.lastError} — falling back to on-device")
            }
        } else {
            parts.add("Engine: On-device")
        }
        return parts.joinToString(" · ")
    }

    private fun memKey(engine: String, norm: String) = "$engine\u0001$norm"

    companion object {
        private const val TAG = "JDT.Engine"
        private const val MEMORY_ENTRIES = 5000

        @Volatile private var instance: TranslationEngine? = null
        fun get(context: Context): TranslationEngine =
            instance ?: synchronized(this) {
                instance ?: TranslationEngine(context.applicationContext).also { instance = it }
            }
    }
}

/** Pure helpers used by the engine (unit-tested on the JVM). */
internal object EngineLogic {

    class Protected(val text: String, val tokens: Map<String, String>)

    private val TOKEN_RE = Regex("""(?i)q\s?z\s?x\s?([a-z])(?![a-z])""")
    private val SEGMENT_RE = Regex("""[^\s|｜/／·•,，、;；]+|[\s|｜/／·•,，、;；]+""")
    private val MULTI_SPACE = Regex(" {2,}")
    private const val MAX_TOKENS = 26

    /**
     * Replaces protected brand terms (longest first) with placeholder tokens MT leaves alone.
     * Returns null if nothing was protected or if the whole string is a single term.
     */
    fun protect(text: String, terms: List<String>, lookup: (String) -> String?): Protected? {
        var s = text
        val tokens = LinkedHashMap<String, String>()
        for (term in terms) {
            if (tokens.size >= MAX_TOKENS) break
            if (term.length < 2 || !s.contains(term)) continue
            val en = lookup(term) ?: continue
            val token = "QZX" + ('A' + tokens.size)
            tokens[token] = en
            s = s.replace(term, " $token ")
        }
        if (tokens.isEmpty()) return null
        s = MULTI_SPACE.replace(s, " ").trim()
        if (!Cjk.hasHan(s)) return null // nothing left for MT; caller translates normally
        return Protected(s, tokens)
    }

    /** Puts English terms back; null if any placeholder went missing (caller retries unprotected). */
    fun restore(translated: String, p: Protected): String? {
        val seen = HashSet<String>()
        val out = TOKEN_RE.replace(translated) { m ->
            val token = "QZX" + m.groupValues[1].uppercase()
            val en = p.tokens[token]
            if (en != null) { seen.add(token); en } else m.value
        }
        if (seen.size != p.tokens.size) return null
        return MULTI_SPACE.replace(out, " ").trim()
    }

    /**
     * If a string is several Han segments joined by separators and every Han segment resolves
     * locally (glossary/rules), composes the English. Otherwise null.
     */
    fun composeSegments(text: String, resolve: (String) -> String?): String? {
        val parts = SEGMENT_RE.findAll(text).map { it.value }.toList()
        val words = parts.filter { !isSeparator(it) }
        if (words.count { Cjk.hasHan(it) } < 2) return null
        val sb = StringBuilder()
        for (part in parts) {
            if (isSeparator(part)) {
                sb.append(mapSeparator(part))
            } else if (Cjk.hasHan(part)) {
                sb.append(resolve(part) ?: return null)
            } else {
                sb.append(part)
            }
        }
        return MULTI_SPACE.replace(sb.toString(), " ").trim()
    }

    private fun isSeparator(s: String) = s.all { it.isWhitespace() || it in "|｜/／·•,，、;；" }

    private fun mapSeparator(sep: String): String {
        val t = sep.trim()
        return when {
            t.isEmpty() -> " "
            t.contains('|') || t.contains('｜') -> " | "
            t.contains('/') || t.contains('／') -> " / "
            t.contains('·') || t.contains('•') -> " · "
            t.contains(';') || t.contains('；') -> "; "
            else -> ", "
        }
    }

    /** Re-applies one leading/trailing space if the original text node had surrounding whitespace. */
    fun keepOuterSpace(original: String, translated: String): String {
        if (original.isEmpty()) return translated
        val lead = original.first().isWhitespace()
        val trail = original.last().isWhitespace()
        if (!lead && !trail) return translated
        return (if (lead) " " else "") + translated.trim() + (if (trail) " " else "")
    }

    /** A backend result is usable if non-blank and not just the untranslated source echoed back. */
    fun isUsable(source: String, translated: String): Boolean {
        val t = translated.trim()
        if (t.isEmpty()) return false
        if (t == source.trim()) return !Cjk.hasHan(t)
        // Mostly-Chinese output means the MT gave up.
        val han = t.count { Cjk.hasHan(it.toString()) }
        return han * 2 < t.length
    }
}
