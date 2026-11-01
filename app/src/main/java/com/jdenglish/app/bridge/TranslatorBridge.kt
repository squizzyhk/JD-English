package com.jdenglish.app.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.jdenglish.app.ocr.ImageTranslator
import com.jdenglish.app.translate.Settings
import com.jdenglish.app.translate.TextItem
import com.jdenglish.app.translate.TranslationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The `JDTNative` JavaScript interface (see docs/ARCHITECTURE.md, "Bridge contract").
 *
 * All @JavascriptInterface methods are invoked on a WebView-private background thread;
 * results are delivered back on the main thread with evaluateJavascript.
 *
 * Page-change handling: [onPageStarted] bumps a generation counter. Work belonging to a
 * previous page is cancelled and its results are dropped, so stale request ids can never
 * be applied to the new document. Because document-start scripts of the new page may call
 * in slightly *before* onPageStarted reaches the main thread, requests that arrived within
 * [GRACE_MS] of the bump are treated as belonging to the new page.
 */
class TranslatorBridge(
    context: Context,
    private val webView: WebView,
    private val listener: Listener,
) {
    interface Listener {
        /** Called on the main thread with the translated document title. */
        fun onTranslatedTitle(title: String)
    }

    private val appContext = context.applicationContext
    private val settings = Settings(appContext)
    private val engine = TranslationEngine.get(appContext)
    private val imageTranslator by lazy { ImageTranslator(appContext, engine) }
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val imageSlots = Semaphore(MAX_IMAGE_JOBS)
    private val batchSlots = Semaphore(MAX_BATCH_JOBS)

    private val generation = AtomicInteger(0)
    private val lastBumpAt = AtomicLong(0L)
    private val jobs = ConcurrentHashMap<Job, Tag>()

    @Volatile private var destroyed = false
    @Volatile private var pageUrl: String? = null
    @Volatile private var userAgent: String? = null

    private data class Tag(val gen: Int, val createdAt: Long)

    // ---------------------------------------------------------------- native-side API (main thread)

    /** Cache the page URL (for Referer/cookies when fetching images). Main thread. */
    fun setPageUrl(url: String?) {
        pageUrl = url
    }

    fun setUserAgent(ua: String?) {
        userAgent = ua
    }

    /** A new document is loading: drop work for the old one. Main thread. */
    fun onPageStarted(url: String?) {
        pageUrl = url
        val now = SystemClock.uptimeMillis()
        val newGen = generation.incrementAndGet()
        lastBumpAt.set(now)
        for ((job, tag) in jobs) {
            if (isStale(tag, newGen, now)) job.cancel()
        }
    }

    /** Cancel everything; the bridge must not be used afterwards. */
    fun destroy() {
        destroyed = true
        scope.cancel()
        jobs.clear()
        main.removeCallbacksAndMessages(null)
    }

    // ---------------------------------------------------------------- JS-side API

    @JavascriptInterface
    fun getConfig(): String = JSONObject()
        .put("enabled", settings.enabled)
        .put("images", imagesAllowed())
        .put("engine", settings.engine)
        .toString()

    @JavascriptInterface
    fun translateBatch(reqId: String?, json: String?) {
        if (destroyed || reqId == null || reqId.length > MAX_REQ_ID) return
        val tag = newTag()
        val texts = parseBatch(json)
        if (texts == null) {
            deliverBatch(reqId, tag, JSONObject().put("items", JSONArray()).put("error", "bad request"))
            return
        }
        if (texts.isEmpty() || texts.size > MAX_BATCH_ITEMS || !settings.enabled) {
            // Not translated: answer with nulls so the page never waits on us.
            deliverBatch(reqId, tag, resultJson(texts.size, emptyList()))
            return
        }
        track(tag) {
            batchSlots.withPermit {
                if (isStale(tag)) return@withPermit
                val indexes = ArrayList<Int>(texts.size)
                val items = ArrayList<TextItem>(texts.size)
                texts.forEachIndexed { i, (t, c) ->
                    if (t.isNotEmpty() && t.length <= MAX_TEXT_LEN) {
                        indexes += i
                        items += TextItem(t, c)
                    }
                }
                val translated: List<String?> = if (items.isEmpty()) emptyList() else try {
                    engine.translate(items)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "translate failed", t)
                    emptyList()
                }
                val out = arrayOfNulls<String>(texts.size)
                indexes.forEachIndexed { k, i -> out[i] = translated.getOrNull(k) }
                deliverBatch(reqId, tag, resultJson(texts.size, out.asList()))
            }
        }
    }

    @JavascriptInterface
    fun translateImage(reqId: String?, url: String?) {
        if (destroyed || reqId == null || reqId.length > MAX_REQ_ID) return
        val tag = newTag()
        val u = url?.trim().orEmpty()
        val lower = u.take(8).lowercase()
        val validScheme = lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("data:")
        when {
            !settings.enabled || !settings.images -> {
                deliverImage(reqId, tag, errorJson("disabled")); return
            }
            !validScheme || u.length > MAX_IMAGE_URL_LEN -> {
                deliverImage(reqId, tag, errorJson("unsupported url")); return
            }
            !imagesAllowed() -> {
                deliverImage(reqId, tag, errorJson("wifi only")); return
            }
        }
        track(tag) {
            imageSlots.withPermit {
                if (isStale(tag)) return@withPermit
                val result = try {
                    imageTranslator.translateImage(u, pageUrl, userAgent)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "image translation failed", t)
                    errorJson(t.message ?: "failed")
                }
                deliverImage(reqId, tag, result)
            }
        }
    }

    @JavascriptInterface
    fun pageTitle(title: String?) {
        if (destroyed) return
        val t = title?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_TITLE_LEN)
        if (t.isNullOrEmpty()) return
        main.post { if (!destroyed) listener.onTranslatedTitle(t) }
    }

    @JavascriptInterface
    fun log(msg: String?) {
        Log.d(JS_TAG, msg?.take(4000) ?: "null")
    }

    // ---------------------------------------------------------------- internals

    private fun newTag() = Tag(generation.get(), SystemClock.uptimeMillis())

    private fun isStale(tag: Tag, currentGen: Int = generation.get(), bumpAt: Long = lastBumpAt.get()): Boolean =
        tag.gen < currentGen && tag.createdAt < bumpAt - GRACE_MS

    private inline fun track(tag: Tag, crossinline block: suspend () -> Unit) {
        if (destroyed) return
        val job = scope.launch { block() }
        jobs[job] = tag
        job.invokeOnCompletion { jobs.remove(job) }
    }

    /** Parses `{"items":[{"t":"..","c":"ui"}]}`; returns null if malformed. */
    private fun parseBatch(json: String?): List<Pair<String, String>>? {
        if (json.isNullOrEmpty()) return null
        return try {
            val arr = JSONObject(json).optJSONArray("items") ?: return null
            if (arr.length() > MAX_BATCH_ITEMS) {
                // Too large to translate; keep only the size so we can answer with nulls.
                return List(arr.length()) { "" to "ui" }
            }
            List(arr.length()) { i ->
                val o = arr.optJSONObject(i)
                val t = o?.optString("t", "").orEmpty()
                val c = o?.optString("c", "ui").orEmpty().ifEmpty { "ui" }.take(16)
                t to c
            }
        } catch (t: Exception) {
            Log.w(TAG, "malformed batch", t)
            null
        }
    }

    private fun resultJson(size: Int, values: List<String?>): JSONObject {
        val arr = JSONArray()
        for (i in 0 until size) arr.put(values.getOrNull(i) ?: JSONObject.NULL)
        return JSONObject().put("items", arr)
    }

    private fun errorJson(msg: String): String = JSONObject().put("error", msg).toString()

    private fun deliverBatch(reqId: String, tag: Tag, result: JSONObject) =
        deliver("onBatch", reqId, tag, result.toString())

    private fun deliverImage(reqId: String, tag: Tag, resultJson: String) {
        // Validate the payload is a JSON object before handing it to JS as a literal.
        val safe = try { JSONObject(resultJson).toString() } catch (_: Exception) { errorJson("bad result") }
        deliver("onImage", reqId, tag, safe)
    }

    private fun deliver(fn: String, reqId: String, tag: Tag, json: String) {
        if (destroyed) return
        // U+2028/2029 are legal in JSON but break older JS parsers inside a script literal.
        val payload = json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        val js = "window.__jdt&&window.__jdt.$fn(" + JSONObject.quote(reqId) + "," + payload + ")"
        main.post {
            if (destroyed || isStale(tag)) return@post
            try {
                webView.evaluateJavascript(js, null)
            } catch (t: Throwable) {
                Log.w(TAG, "evaluateJavascript failed", t)
            }
        }
    }

    private fun imagesAllowed(): Boolean {
        if (!settings.images) return false
        if (!settings.imagesWifiOnly) return true
        return isUnmetered()
    }

    private fun isUnmetered(): Boolean = try {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    } catch (_: Exception) {
        false
    }

    companion object {
        const val JS_NAME = "JDTNative"
        private const val TAG = "JDT.Bridge"
        private const val JS_TAG = "JDT.js"
        private const val MAX_BATCH_ITEMS = 500
        private const val MAX_TEXT_LEN = 5000
        private const val MAX_REQ_ID = 200
        private const val MAX_TITLE_LEN = 300
        private const val MAX_IMAGE_URL_LEN = 8_000_000 // data: URLs can be large
        private const val MAX_IMAGE_JOBS = 2
        private const val MAX_BATCH_JOBS = 4
        private const val GRACE_MS = 400L
    }
}
