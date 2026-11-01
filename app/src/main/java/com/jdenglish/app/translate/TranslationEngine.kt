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
    suspend fun translate(items: List<TextItem>): List<String?> = translateWithInfo(items).values

    /**
     * Result of [translateWithInfo].
     * @property fellBack the Claude engine was selected and usable but some strings came from ML Kit
     *   instead (a Claude failure), so the result shouldn't be cached as Claude output.
     * @property backendUnavailable some strings are null because the last backend tier couldn't run
     *   (e.g. the ML Kit model is still downloading); retrying later may succeed. When false, a
     *   null means the backend answered with something unusable (retrying won't help).
     */
    class Outcome(val values: List<String?>, val fellBack: Boolean, val backendUnavailable: Boolean)

    /** Like [translate], plus how the result was produced (used by OCR result caching). */
    suspend fun translateWithInfo(items: List<TextItem>): Outcome {
        if (items.isEmpty()) return Outcome(emptyList(), fellBack = false, backendUnavailable = false)
        return try {
            translateImpl(items)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "translate failed", t)
            Outcome(items.map { null }, fellBack = false, backendUnavailable = true)
        }
    }

    private class Pending(val norm: String, val ctx: String) {
        val indices = ArrayList<Int>(1)
        var result: String? = null
    }

    private suspend fun translateImpl(items: List<TextItem>): Outcome {
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
                // Claude's output depends on ctx (title vs ui vs image), so dedupe per ctx.
                pending.getOrPut(norm + "\u0001" + item.ctx) { Pending(norm, item.ctx) }.indices.add(i)
            }
        }
        if (pending.isEmpty()) return Outcome(out.toList(), fellBack = false, backendUnavailable = false)

        val useClaude = settings.engine == ClaudeBackend.ID && claude.hasKey
        var unresolved: List<Pending> = pending.values.toList()
        var fellBack = false
        if (useClaude) {
            // Cache Claude output per model and ctx: switching model refreshes, and a ctx="title"
            // rendering isn't reused for ctx="ui" (ML Kit ignores ctx, so its key is the text alone).
            val claudeId = claude.id + ":" + settings.claudeModel
            unresolved = runTier(claudeId, unresolved, { it.ctx + "\u0001" + it.norm }) { batch ->
                claude.translate(batch.map { TextItem(it.norm, it.ctx) })
            }
            fellBack = unresolved.isNotEmpty()
        }
        if (unresolved.isNotEmpty()) {
            // Default engine, or fallback when Claude failed / has no key.
            unresolved = runTier(mlKit.id, unresolved, { it.norm }) { batch -> translateWithMlKit(batch, g) }
        }
        val backendUnavailable = unresolved.isNotEmpty() && !mlKit.isReadyNow

        for (p in pending.values) {
            val v = p.result ?: continue
            for (i in p.indices) {
                val item = items[i]
                out[i] = EngineLogic.keepOuterSpace(item.text, PostEditor.finish(v, item.ctx, item.text))
            }
        }
        return Outcome(out.toList(), fellBack, backendUnavailable)
    }

    /**
     * Memory -> SQLite -> backend for one engine id. [keyOf] is the cache key of an entry within
     * that engine. Returns the entries still unresolved.
     */
    private suspend fun runTier(
        engineId: String,
        input: List<Pending>,
        keyOf: (Pending) -> String,
        backend: suspend (List<Pending>) -> List<String?>,
    ): List<Pending> {
        var misses = ArrayList<Pending>()
        for (p in input) {
            val hit = memory.get(memKey(engineId, keyOf(p)))
            if (hit != null) p.result = hit else misses.add(p)
        }
        if (misses.isEmpty()) return emptyList()

        val dbHits = cache.getAll(engineId, misses.map(keyOf))
        if (dbHits.isNotEmpty()) {
            val still = ArrayList<Pending>()
            for (p in misses) {
                val hit = dbHits[keyOf(p)]
                if (hit != null) {
                    p.result = hit
                    memory.put(memKey(engineId, keyOf(p)), hit)
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
                memory.put(memKey(engineId, keyOf(p)), cleaned)
                toStore[keyOf(p)] = cleaned
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

    /**
     * Process start: glossary load + model download in the background. Only the first call does
     * anything; use [downloadModel] for an explicit (repeatable) download request.
     */
    fun warmUp() {
        if (!warmedUp.compareAndSet(false, true)) return
        scope.launch {
            try {
                glossary()
                mlKit.refreshDownloadedState()
                // Claude users only need ML Kit as a fallback; it downloads on first fallback use.
                if (!(settings.engine == ClaudeBackend.ID && claude.hasKey)) mlKit.ensureReady()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "warmUp failed", t)
            }
        }
    }

    /**
     * Explicit download request (Settings "Download offline model", engine change): skips the
     * failure back-off and starts the ML Kit model download in the background. Repeatable.
     */
    fun downloadModel() {
        scope.launch { glossary() }
        mlKit.downloadNow()
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
            if (term.length < 2) continue
            val at = termOccurrences(s, term)
            if (at.isEmpty()) continue
            val en = lookup(term) ?: continue
            val token = "QZX" + ('A' + tokens.size)
            tokens[token] = en
            val sb = StringBuilder(s.length + at.size * 6)
            var last = 0
            for (i in at) {
                sb.append(s, last, i).append(" ").append(token).append(" ")
                last = i + term.length
            }
            s = sb.append(s, last, s.length).toString()
        }
        if (tokens.isEmpty()) return null
        s = MULTI_SPACE.replace(s, " ").trim()
        if (!Cjk.hasHan(s)) return null // nothing left for MT; caller translates normally
        return Protected(s, tokens)
    }

    /**
     * Contexts in which a glossary term's characters are really part of another word, e.g.
     * 京东 inside 北京东城区 / 南京东路 / 北京东站, or 金条 in 投资金条 (gold bars, not JD's loan).
     * A match of the regex (exactly the term, with look-arounds) is not an occurrence of the term.
     */
    private val TERM_EXCLUSIONS: Map<String, Regex> = mapOf(
        "京东" to Regex("""(?<=[北南])京东|京东(?=[城路站街区])"""),
        "金条" to Regex("""(?<=[资黄足纯金银])金条|金条(?=\s*[0-9０-９克gG])"""),
    )

    /** Start indexes of real (non-overlapping) occurrences of [term] in [text]. */
    fun termOccurrences(text: String, term: String): List<Int> {
        if (term.isEmpty()) return emptyList()
        var i = text.indexOf(term)
        if (i < 0) return emptyList()
        val excluded = TERM_EXCLUSIONS[term]?.findAll(text)?.map { it.range.first }?.toHashSet()
        val out = ArrayList<Int>(2)
        while (i >= 0) {
            if (excluded == null || i !in excluded) out.add(i)
            i = text.indexOf(term, i + term.length)
        }
        return out
    }

    /** True if [text] contains a real occurrence of [term] (see [termOccurrences]). */
    fun containsTerm(text: String, term: String): Boolean =
        text.contains(term) && termOccurrences(text, term).isNotEmpty()

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
