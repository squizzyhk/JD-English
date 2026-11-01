package com.jdenglish.app.translate

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Optional high-quality backend: Anthropic Messages API (raw HTTPS via OkHttp).
 * Batches up to ~60 strings / ~6000 chars per request and asks for a strict JSON array back.
 * Returns null for any string it could not translate; the engine falls back to ML Kit for those.
 */
class ClaudeBackend(
    private val apiKey: () -> String,
    private val model: () -> String,
    private val glossary: () -> Glossary?,
    private val client: OkHttpClient = defaultClient,
) : TranslationBackend {
    override val id: String = ID

    /** Last error (null after a successful request), for the status line. */
    @Volatile var lastError: String? = null
        private set

    private val parallel = Semaphore(MAX_PARALLEL_REQUESTS)

    /** After an auth/model error, skip calls for a while (keyed to the key+model that failed). */
    @Volatile private var fatalUntil = 0L
    @Volatile private var fatalFor = ""
    private fun configId() = apiKey().hashCode().toString() + "|" + model()
    private fun inFatalBackoff() = System.currentTimeMillis() < fatalUntil && fatalFor == configId()

    val hasKey: Boolean get() = apiKey().isNotBlank()

    override suspend fun translate(items: List<TextItem>): List<String?> {
        if (items.isEmpty()) return emptyList()
        if (!hasKey) {
            lastError = "No Claude API key set"
            return items.map { null }
        }
        if (inFatalBackoff()) return items.map { null }
        val out = arrayOfNulls<String>(items.size)
        val chunks = ClaudeProtocol.chunk(items.indices.toList(), { items[it].text.length }, MAX_ITEMS, MAX_CHARS)
        coroutineScope {
            chunks.map { idx ->
                async {
                    parallel.withPermit {
                        val res = translateChunk(idx.map { items[it] }, allowSplit = true)
                        idx.forEachIndexed { k, i -> out[i] = res[k] }
                    }
                }
            }.awaitAll()
        }
        return out.toList()
    }

    /**
     * One request; on a malformed/mismatched response, retries once with halves (in parallel).
     * Transport/HTTP failures and refusals are not split: they return nulls at once so the
     * engine can fall back to ML Kit without minutes of retries.
     */
    private suspend fun translateChunk(chunk: List<TextItem>, allowSplit: Boolean): List<String?> {
        var gotResponse = false
        val result = try {
            val hints = ClaudeProtocol.glossaryHints(chunk.map { it.text }, glossary()?.entries().orEmpty(), MAX_HINTS)
            val user = ClaudeProtocol.buildUserMessage(chunk, hints)
            val chars = chunk.sumOf { it.text.length }
            val maxTokens = (1024 + chars * 4).coerceAtMost(16000)
            val text = call(SYSTEM_PROMPT, user, maxTokens)
            gotResponse = text != null
            text?.let { ClaudeProtocol.parseArray(it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: FatalApiException) {
            fatalUntil = System.currentTimeMillis() + FATAL_BACKOFF_MS
            fatalFor = configId()
            return chunk.map { null } // auth/config problem: splitting won't help
        } catch (e: Throwable) {
            Log.w(TAG, "chunk failed: ${e.message}")
            null
        }
        if (result != null && result.size == chunk.size) {
            return result.mapIndexed { i, s ->
                val v = s?.trim()
                // Reject echoes of untranslated Chinese so the engine can fall back.
                if (v.isNullOrEmpty() || (Cjk.hasHan(v) && v == chunk[i].text.trim())) null else v
            }
        }
        if (!gotResponse) return chunk.map { null }
        if (allowSplit && chunk.size > 1) {
            Log.w(TAG, "length mismatch (${result?.size} vs ${chunk.size}); retrying in halves")
            val mid = chunk.size / 2
            return coroutineScope {
                val a = async { translateChunk(chunk.subList(0, mid), false) }
                val b = async { translateChunk(chunk.subList(mid, chunk.size), false) }
                a.await() + b.await()
            }
        }
        if (result != null && chunk.size == 1 && result.isNotEmpty()) return listOf(result.first()?.trim()?.ifEmpty { null })
        return chunk.map { null }
    }

    override suspend fun translateToChinese(text: String): String? {
        if (!hasKey || text.isBlank() || inFatalBackoff()) return null
        return try {
            val out = call(SEARCH_PROMPT, text.trim().take(500), 1024) ?: return null
            ClaudeProtocol.cleanQuery(out)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "toChinese failed: ${e.message}")
            null
        }
    }

    private class FatalApiException(msg: String) : Exception(msg)

    /** POST /v1/messages; returns concatenated text blocks, or null on a non-retryable failure. */
    private suspend fun call(system: String, user: String, maxTokens: Int): String? = withContext(Dispatchers.IO) {
        val modelId = model().trim().ifEmpty { Settings.DEFAULT_CLAUDE_MODEL }
        var useEffort = ClaudeProtocol.supportsEffort(modelId)
        var attempt = 0
        while (true) {
            attempt++
            val body = ClaudeProtocol.buildRequestBody(modelId, system, user, maxTokens, useEffort)
            val req = Request.Builder()
                .url(API_URL)
                .header("x-api-key", apiKey())
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json")
                .post(body.toRequestBody(JSON_MEDIA))
                .build()
            val (code, respBody, retryAfter) = try {
                client.newCall(req).await().use { r ->
                    Triple(r.code, r.body?.string().orEmpty(), r.header("retry-after")?.toLongOrNull())
                }
            } catch (e: IOException) {
                lastError = "Network error contacting Claude: ${e.message ?: e.javaClass.simpleName}"
                // A timeout already cost up to readTimeout; don't spend that twice.
                if (attempt == 1 && e !is java.io.InterruptedIOException) { delay(1500); continue }
                throw e
            }
            when {
                code in 200..299 -> {
                    val json = JSONObject(respBody)
                    val stop = json.optString("stop_reason")
                    if (stop == "refusal") {
                        lastError = "Claude declined this request"
                        return@withContext null
                    }
                    lastError = null
                    val text = ClaudeProtocol.extractText(json)
                    if (stop == "max_tokens") Log.w(TAG, "response hit max_tokens")
                    return@withContext text
                }
                code == 400 && useEffort && respBody.contains("effort", ignoreCase = true) -> {
                    useEffort = false // model doesn't take output_config.effort; resend without it
                    attempt--
                    continue
                }
                code == 401 || code == 403 -> {
                    lastError = "Claude API key was rejected (HTTP $code)"
                    throw FatalApiException(lastError!!)
                }
                code == 404 -> {
                    lastError = "Claude model \"$modelId\" is not available (HTTP $code)"
                    throw FatalApiException(lastError!!)
                }
                (code == 429 || code == 529 || code >= 500) && attempt == 1 -> {
                    val waitMs = ((retryAfter ?: 2L) * 1000L).coerceIn(1000L, 15_000L)
                    lastError = "Claude is busy (HTTP $code); retrying"
                    delay(waitMs)
                    continue
                }
                else -> {
                    lastError = "Claude request failed (HTTP $code)"
                    Log.w(TAG, "HTTP $code: ${respBody.take(300)}")
                    return@withContext null
                }
            }
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    companion object {
        const val ID = "claude"
        private const val TAG = "JDT.Claude"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"
        private val JSON_MEDIA = "application/json".toMediaType()
        const val MAX_ITEMS = 60
        const val MAX_CHARS = 6000
        private const val MAX_HINTS = 80
        private const val MAX_PARALLEL_REQUESTS = 3
        private const val FATAL_BACKOFF_MS = 60_000L

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                // Non-streaming: no bytes arrive until the whole (up to 16k-token) reply is done.
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(150, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        val SYSTEM_PROMPT = """
You are the localization engine of an English shopping app for JD.com (China). The app shows JD's Chinese mobile site to English-speaking shoppers, replacing every Chinese string in place, so your output appears directly on screen.

Translate each input string into natural, concise US e-commerce English, the way Amazon or AliExpress would phrase it.

Rules:
- Keep brand names, model numbers, SKUs, units, sizes, prices, percentages and all numbers exactly. Use well-known English brand names where they exist (华为 = Huawei, 小米 = Xiaomi, 苹果 = Apple when it is the brand). Prices stay in ¥.
- JD terms: 京东 = JD, 京东自营 / 自营 = JD Direct, 京东物流 = JD Logistics, PLUS会员 = PLUS Member, 京豆 = JD Beans, 白条 = JD Baitiao (credit), 百亿补贴 = Big Subsidy Deals, 包邮 = Free Shipping, 秒杀 = Flash Sale, 券 / 优惠券 = coupon, 旗舰店 = Official Store.
- Each item has a type:
  ui = interface text (buttons, tabs, menus, labels, notices). Keep it short. Title Case for buttons, tabs and menu items; sentence case for messages.
  title = product title. Produce a readable, title-cased product name: brand first, then product, then key specs. Keep every spec (capacity, size, color, model, quantity). Drop keyword-stuffing duplicates only when clearly redundant.
  product = product or store description. Fluent, faithful prose.
  attr = specification name or value (颜色 = Color, 尺码 = Size). Very short.
  review = customer review or Q&A. Faithful, natural first-person English; keep the tone.
  image = text printed on a picture (banner, promo, label). Short and punchy.
- Use glossary terms when provided, for consistent terminology.
- If a string is already English or is only a brand/model, return it unchanged.
- Never add commentary, notes, quotes or explanations.

Output: a STRICT JSON array of strings, same length and same order as the input items, and nothing else.
""".trim()

        val SEARCH_PROMPT = """
You convert an English shopping search into the best Simplified Chinese search query for JD.com. Use the terms Chinese shoppers type into JD's search box; keep brand names and model numbers as commonly written on JD (e.g. "iPhone 15 Pro", "华为 Mate 60"). Reply with the query only: plain text, no quotes, no explanation.
""".trim()
    }
}

/** Pure request/response helpers (unit-tested on the JVM). */
internal object ClaudeProtocol {

    private val CODE_FENCE = Regex("```(?:json)?", RegexOption.IGNORE_CASE)
    private val MODEL_RE = Regex("""claude-(opus|sonnet|haiku|fable|mythos)-(\d+)(?:-(\d{1,2}))?(?!\d)""")

    /** Splits indices into chunks of at most [maxItems] items and roughly [maxChars] characters. */
    fun <T> chunk(items: List<T>, size: (T) -> Int, maxItems: Int, maxChars: Int): List<List<T>> {
        val out = ArrayList<List<T>>()
        var cur = ArrayList<T>()
        var chars = 0
        for (it in items) {
            val n = size(it)
            if (cur.isNotEmpty() && (cur.size >= maxItems || chars + n > maxChars)) {
                out.add(cur); cur = ArrayList(); chars = 0
            }
            cur.add(it); chars += n
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    /** Glossary entries whose zh key occurs in any batch string; longest keys first, capped. */
    fun glossaryHints(texts: List<String>, entries: Map<String, String>, max: Int): List<Pair<String, String>> {
        if (entries.isEmpty() || texts.isEmpty()) return emptyList()
        val joined = texts.joinToString("\u0001")
        val hits = ArrayList<Pair<String, String>>()
        for ((zh, en) in entries) {
            if (zh.length < 2 || !Cjk.hasHan(zh)) continue
            if (EngineLogic.containsTerm(joined, zh)) hits.add(zh to en)
        }
        // Prefer longer (more specific) terms when capping.
        hits.sortWith(compareByDescending<Pair<String, String>> { it.first.length }.thenBy { it.first })
        return if (hits.size > max) hits.subList(0, max).toList() else hits
    }

    fun buildUserMessage(chunk: List<TextItem>, hints: List<Pair<String, String>>): String {
        val sb = StringBuilder()
        if (hints.isNotEmpty()) {
            sb.append("Glossary (Chinese = English):\n")
            for ((zh, en) in hints) sb.append(zh).append(" = ").append(en).append('\n')
            sb.append('\n')
        }
        val arr = JSONArray()
        chunk.forEachIndexed { i, it ->
            arr.put(JSONObject().put("i", i + 1).put("type", normalizeCtx(it.ctx)).put("zh", it.text))
        }
        sb.append("Translate these ").append(chunk.size).append(" items. Reply with a JSON array of exactly ")
            .append(chunk.size).append(" English strings in the same order.\n")
        sb.append(arr.toString())
        return sb.toString()
    }

    fun normalizeCtx(ctx: String): String = when (ctx.lowercase()) {
        "ui", "title", "product", "attr", "review", "image" -> ctx.lowercase()
        else -> "ui"
    }

    /** Effort is accepted by Opus 4.5+, Sonnet 4.6+, Haiku 5+, and every 5.x model. */
    fun supportsEffort(model: String): Boolean {
        val m = MODEL_RE.find(model) ?: return false
        val family = m.groupValues[1]
        val major = m.groupValues[2].toIntOrNull() ?: return false
        val minor = m.groupValues[3].toIntOrNull() ?: 0
        if (major >= 5) return true
        if (major == 4) return when (family) {
            "opus" -> minor >= 5
            "sonnet" -> minor >= 6
            else -> false
        }
        return false
    }

    fun buildRequestBody(model: String, system: String, user: String, maxTokens: Int, effort: Boolean): String {
        val o = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        // Translation is a low-reasoning task: low effort keeps latency and cost down.
        // (No temperature: current models reject non-default sampling parameters.)
        if (effort) o.put("output_config", JSONObject().put("effort", "low"))
        return o.toString()
    }

    /** Concatenates all `text` content blocks (thinking blocks may precede them). */
    fun extractText(response: JSONObject): String? {
        val content = response.optJSONArray("content") ?: return null
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val b = content.optJSONObject(i) ?: continue
            if (b.optString("type") == "text") sb.append(b.optString("text"))
        }
        return sb.toString().ifEmpty { null }
    }

    /** Robustly parses a JSON array of strings from model text; null if none can be found. */
    fun parseArray(raw: String): List<String?>? {
        val s = raw.replace(CODE_FENCE, "").trim()
        val start = s.indexOf('[')
        val end = s.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        val arr = try {
            JSONArray(s.substring(start, end + 1))
        } catch (_: Throwable) {
            return null
        }
        return (0 until arr.length()).map { i ->
            when (val v = arr.opt(i)) {
                is String -> v
                is JSONObject -> v.optString("en").ifEmpty { v.optString("text") }.ifEmpty { null }
                null, JSONObject.NULL -> null
                else -> v.toString()
            }
        }
    }

    /** First line of a model reply, stripped of quotes/labels. */
    fun cleanQuery(raw: String): String? {
        val line = raw.replace(CODE_FENCE, "").trim().lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: return null
        return line.removePrefix("Query:").removePrefix("查询：").trim()
            .trim('"', '\'', '“', '”', '「', '」')
            .trim()
            .ifEmpty { null }
    }
}

/** Suspends on an OkHttp call; cancels the call if the coroutine is cancelled. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { try { cancel() } catch (_: Throwable) {} }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}
