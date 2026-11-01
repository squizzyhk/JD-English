package com.jdenglish.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.ConnectivityManager
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.jdenglish.app.translate.Settings
import com.jdenglish.app.translate.TextItem
import com.jdenglish.app.translate.TranslationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * Downloads an image (with the WebView's cookies + Referer), runs ML Kit Chinese OCR,
 * groups lines into blocks, translates via TranslationEngine (ctx="image"), samples
 * fg/bg colors, and returns the JSON described in docs/ARCHITECTURE.md ("Image result JSON").
 * OWNER: OCR.
 *
 * Heavy shared state (OkHttp client, OCR recognizer, result caches) lives in the companion,
 * so creating several ImageTranslator instances is cheap.
 */
class ImageTranslator(private val context: Context, private val engine: TranslationEngine) {

    private val appContext: Context = context.applicationContext
    private val fetcher = ImageFetcher(appContext)
    private val cache: OcrResultCache = resultCache(appContext)

    /** Never throws (except coroutine cancellation); returns {"error":...} JSON on failure. */
    suspend fun translateImage(url: String, pageUrl: String?, userAgent: String?): String {
        val started = SystemClock.elapsedRealtime()
        return try {
            withContext(Dispatchers.Default) { translateCached(url, pageUrl, userAgent) }
                .also { Log.d(TAG, "image done in ${SystemClock.elapsedRealtime() - started}ms: ${shorten(url)}") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "OOM translating ${shorten(url)}")
            errorJson("Out of memory")
        } catch (e: Throwable) {
            Log.w(TAG, "image failed: ${shorten(url)}: ${e.message}")
            errorJson(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Drops cached OCR results (memory and disk), e.g. from Settings -> Clear cache. */
    fun clearCache() = cache.clear()

    private suspend fun translateCached(rawUrl: String, pageUrl: String?, userAgent: String?): String {
        val url = JdImageUrls.normalize(rawUrl)
        if (url.isEmpty()) return errorJson("Empty URL")
        val scheme = url.substringBefore(':').lowercase()
        if (scheme != "http" && scheme != "https" && scheme != "data") return errorJson("Unsupported URL")

        // Key by the engine that will actually run: "claude" without a key translates with ML Kit.
        val engineId = try {
            val st = Settings(appContext)
            if (st.claudeReady) "claude:" + st.claudeModel else "mlkit"
        } catch (e: Exception) { "mlkit" }
        val key = cache.key(url, engineId)
        cache.get(key)?.let { return it }

        // Same image requested twice at once (several frames, re-renders): do the work once.
        return lockFor(key).withLock {
            cache.get(key) ?: withContext(Dispatchers.IO) { compute(url, pageUrl, userAgent) }
                .also { r -> if (r.cacheable) cache.put(key, r.json) }
                .json
        }
    }

    private class Result(val json: String, val cacheable: Boolean)

    private class Candidate(val block: OcrBlock, val box: IRect, val colors: ColorSampler.Colors)

    private suspend fun compute(url: String, pageUrl: String?, userAgent: String?): Result {
        // 1) Download (+ JD CDN fallbacks), outside the CPU/memory permit.
        val cacheOnly = Settings(appContext).imagesWifiOnly && isMetered()
        val candidates = JdImageUrls.candidates(url)
        var lastError: String? = null
        var w = 0
        var h = 0
        var found: List<Candidate>? = null

        for (candidate in candidates) {
            coroutineContext.ensureActive()
            val bytes = try {
                fetcher.fetch(candidate, pageUrl, userAgent, cacheOnly)
            } catch (e: ImageFetchException) {
                lastError = e.message; continue
            } catch (e: java.io.IOException) {
                lastError = e.message ?: "Network error"; continue
            }
            // 2) Decode, OCR and sample colors while holding a permit (bounds peak memory/CPU).
            val outcome = heavyPermits.withPermit { decodeAndRecognize(bytes) }
            if (outcome == null) {
                lastError = "Unsupported image format"; continue
            }
            w = outcome.first; h = outcome.second; found = outcome.third
            break
        }
        val blocks = found ?: return Result(errorJson(lastError ?: "Could not load image"), false)
        if (blocks.isEmpty()) return Result(resultJson(w, h, emptyList()), true)

        // 3) Translate all blocks in one batch.
        val outcome = engine.translateWithInfo(blocks.map { TextItem(it.block.text, "image") })
        val translations = outcome.values
        var missing = 0
        val out = JSONArray()
        blocks.forEachIndexed { i, c ->
            val en = translations.getOrNull(i)?.trim()
            if (en.isNullOrEmpty()) { missing++; return@forEachIndexed }
            if (en == c.block.text) return@forEachIndexed
            out.put(
                JSONObject()
                    .put("x", c.box.left).put("y", c.box.top)
                    .put("w", c.box.width).put("h", c.box.height)
                    .put("text", en)
                    .put("orig", c.block.text)
                    .put("fg", c.colors.fgHex)
                    .put("bg", c.colors.bgHex)
                    .put("angle", (c.block.angle * 10).roundToInt() / 10.0)
            )
        }
        // Blocks missing because the backend couldn't run (offline model still downloading, ...):
        // tell JS it may retry later, and don't persist. Blocks the backend answered unusably
        // (OCR noise echoed back) are final. Claude->ML Kit fallback output isn't stored under
        // the Claude key either.
        val partial = missing > 0 && outcome.backendUnavailable
        val json = JSONObject().put("w", w).put("h", h).put("blocks", out)
        if (partial) json.put("partial", true)
        return Result(json.toString(), !partial && !outcome.fellBack)
    }

    /** Returns (w, h, candidates), or null if the bytes don't decode. Recycles every bitmap it makes. */
    private suspend fun decodeAndRecognize(bytes: ByteArray): Triple<Int, Int, List<Candidate>>? {
        val bitmap = ImageDecoding.decode(bytes) ?: return null
        val w = bitmap.width
        val h = bitmap.height
        val pending = PendingTask()
        try {
            if (w < MIN_W || h < MIN_H) return Triple(w, h, emptyList())

            val raw = recognizeTiles(bitmap, pending)
            val lines = BlockGrouper.dedupe(raw)
                .filter { BlockGrouper.hasHan(it.text) && it.rect.height >= MIN_LINE_PX }
            val blocks = BlockGrouper.group(lines).filter { BlockGrouper.hasHan(it.text) }
            val result = blocks.map { b ->
                coroutineContext.ensureActive()
                val colors = BitmapColorSampler.sample(bitmap, b.rect)
                Candidate(b, b.rect.outset(BOX_PAD).clamp(w, h), colors)
            }
            return Triple(w, h, result)
        } finally {
            // If cancelled mid-OCR, ML Kit may still be reading the bitmap: recycle when it's done.
            val t = pending.task
            if (t != null && !t.isComplete) t.addOnCompleteListener(DIRECT) { bitmap.recycle() } else bitmap.recycle()
        }
    }

    private class PendingTask { @Volatile var task: Task<*>? = null }

    /** OCR each tile; returns lines in the source bitmap's coordinate space. */
    private suspend fun recognizeTiles(src: Bitmap, pending: PendingTask): List<OcrLine> {
        val w = src.width
        val h = src.height
        val lines = ArrayList<OcrLine>()
        for (tile in TilePlanner.plan(w, h)) {
            coroutineContext.ensureActive()
            val tileBmp = if (tile.top == 0 && tile.height == h && tile.scale >= 0.999f) {
                src
            } else {
                val m = Matrix().apply { if (tile.scale < 0.999f) setScale(tile.scale, tile.scale) }
                Bitmap.createBitmap(src, 0, tile.top, w, tile.height, m, true)
            }
            val owned = tileBmp !== src
            // Real scale after integer rounding of the scaled tile size.
            val sx = tileBmp.width.toFloat() / w
            val sy = tileBmp.height.toFloat() / tile.height
            val task = recognizer.process(InputImage.fromBitmap(tileBmp, 0))
            pending.task = task
            if (owned) task.addOnCompleteListener(DIRECT) { tileBmp.recycle() }
            val text: Text = task.await()
            for (block in text.textBlocks) for (line in block.lines) {
                val r = line.boundingBox ?: continue
                val t = BlockGrouper.cleanText(line.text)
                if (t.isEmpty()) continue
                val rect = IRect(
                    (r.left / sx).toInt(),
                    (r.top / sy).toInt() + tile.top,
                    (r.right / sx).roundToInt(),
                    (r.bottom / sy).roundToInt() + tile.top,
                ).clamp(w, h)
                if (rect.isEmpty) continue
                val angle = try { line.angle } catch (e: Throwable) { 0f }
                lines += OcrLine(t, rect, if (angle.isNaN()) 0f else angle)
            }
        }
        return lines
    }

    private fun isMetered(): Boolean = try {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm?.isActiveNetworkMetered ?: false
    } catch (e: Exception) {
        false // e.g. missing ACCESS_NETWORK_STATE permission
    }

    companion object {
        private const val TAG = "JDT-OCR"
        private const val MIN_W = 60
        private const val MIN_H = 30
        private const val MIN_LINE_PX = 6
        private const val BOX_PAD = 2

        private val DIRECT = Executor { it.run() }

        /** Decode+OCR is CPU and memory heavy; allow two at a time. */
        private val heavyPermits = Semaphore(2)

        private val locks = Array(32) { Mutex() }
        private fun lockFor(key: String) = locks[(key.hashCode() and 0x7FFFFFFF) % locks.size]

        private val recognizer: TextRecognizer by lazy {
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        }

        @Volatile private var sharedCache: OcrResultCache? = null
        private fun resultCache(ctx: Context): OcrResultCache = sharedCache ?: synchronized(this) {
            sharedCache ?: OcrResultCache(File(ctx.cacheDir, "ocr-results")).also { sharedCache = it }
        }

        internal fun errorJson(message: String): String = JSONObject().put("error", message).toString()

        private fun resultJson(w: Int, h: Int, blocks: List<JSONObject>): String =
            JSONObject().put("w", w).put("h", h).put("blocks", JSONArray(blocks)).toString()

        private fun shorten(url: String) = if (url.length > 120) url.take(117) + "..." else url

        private suspend fun <T> Task<T>.await(): T {
            if (isComplete) {
                exception?.let { throw it }
                if (isCanceled) throw CancellationException("OCR task cancelled")
                @Suppress("UNCHECKED_CAST")
                return result as T
            }
            return suspendCancellableCoroutine { cont ->
                addOnCompleteListener(DIRECT) { t ->
                    val e = t.exception
                    when {
                        e != null -> cont.resumeWithException(e)
                        t.isCanceled -> cont.cancel()
                        else -> {
                            @Suppress("UNCHECKED_CAST")
                            cont.resume(t.result as T)
                        }
                    }
                }
            }
        }
    }
}

/** Thin Android wrapper over [ColorSampler]: copies the block's neighbourhood out of the bitmap. */
internal object BitmapColorSampler {
    private const val MARGIN = 4
    private const val MAX_REGION_PX = 4_000_000

    fun sample(bitmap: Bitmap, box: IRect): ColorSampler.Colors {
        val region = box.outset(MARGIN).clamp(bitmap.width, bitmap.height)
        if (region.isEmpty) return ColorSampler.Colors(ColorSampler.BLACK, ColorSampler.WHITE)
        if (region.area > MAX_REGION_PX) {
            // Huge block: sample a downscaled copy of the region instead.
            val f = kotlin.math.sqrt(MAX_REGION_PX.toDouble() / region.area).toFloat()
            val m = Matrix().apply { setScale(f, f) }
            val small = Bitmap.createBitmap(bitmap, region.left, region.top, region.width, region.height, m, true)
            try {
                val sw = small.width; val sh = small.height
                val px = IntArray(sw * sh)
                small.getPixels(px, 0, sw, 0, 0, sw, sh)
                val local = box.offset(-region.left, -region.top)
                val scaled = IRect((local.left * f).toInt(), (local.top * f).toInt(), (local.right * f).toInt(), (local.bottom * f).toInt())
                return ColorSampler.sample(px, sw, sh, scaled)
            } finally {
                if (small !== bitmap) small.recycle()
            }
        }
        val px = IntArray(region.width * region.height)
        bitmap.getPixels(px, 0, region.width, region.left, region.top, region.width, region.height)
        return ColorSampler.sample(px, region.width, region.height, box.offset(-region.left, -region.top))
    }
}
