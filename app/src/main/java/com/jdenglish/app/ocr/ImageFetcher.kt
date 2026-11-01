package com.jdenglish.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Thrown when an image can't be fetched; [message] is short and user-presentable in logs. */
internal class ImageFetchException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Downloads image bytes the way the WebView would (same User-Agent, Referer and cookies) through
 * a shared OkHttp client with a disk cache, with a hard size cap. Also decodes data: URLs.
 */
internal class ImageFetcher(context: Context) {

    private val client: OkHttpClient = sharedClient(context.applicationContext)

    /**
     * @param cacheOnly serve only from the HTTP disk cache (used on metered networks when the
     *   user asked not to re-download images); throws if not cached.
     */
    suspend fun fetch(url: String, pageUrl: String?, userAgent: String?, cacheOnly: Boolean = false): ByteArray {
        if (url.startsWith("data:", ignoreCase = true)) return decodeDataUrl(url)
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) {
            throw ImageFetchException("Unsupported URL scheme")
        }
        val builder = Request.Builder().url(url)
            .header("Accept", acceptHeader())
            .header("Referer", pageUrl?.takeIf { it.startsWith("http", true) } ?: DEFAULT_REFERER)
        if (!userAgent.isNullOrBlank()) builder.header("User-Agent", userAgent)
        // Cookies come from WebViewCookieJar, per hop: a hand-set Cookie header would be kept
        // by OkHttp on a cross-host redirect and leak the JD session to the redirect target.
        if (cacheOnly) builder.cacheControl(CacheControl.FORCE_CACHE)

        val call = client.newCall(builder.build())
        val response = call.await()
        response.use { resp ->
            if (resp.code == 504 && cacheOnly) throw ImageFetchException("Not cached (metered network)")
            if (!resp.isSuccessful) throw ImageFetchException("HTTP ${resp.code}")
            val body = resp.body ?: throw ImageFetchException("Empty response")
            val type = body.contentType()
            if (type != null && (type.type == "text" || type.subtype.contains("json") || type.subtype.contains("html"))) {
                throw ImageFetchException("Not an image ($type)")
            }
            val declared = body.contentLength()
            if (declared > MAX_BYTES) throw ImageFetchException("Image too large ($declared bytes)")
            val source = body.source()
            val buf = Buffer()
            try {
                while (true) {
                    coroutineContext.ensureActive()
                    val n = source.read(buf, 64 * 1024L)
                    if (n == -1L) break
                    if (buf.size > MAX_BYTES) throw ImageFetchException("Image too large (> $MAX_BYTES bytes)")
                }
            } catch (e: IOException) {
                call.cancel()
                throw e
            }
            if (buf.size == 0L) throw ImageFetchException("Empty image")
            return buf.readByteArray()
        }
    }

    private fun acceptHeader(): String =
        if (Build.VERSION.SDK_INT >= 31) "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
        else "image/webp,image/apng,image/*,*/*;q=0.8"

    /** Read-only bridge to the WebView's cookies, evaluated for each request URL (incl. redirects). */
    private object WebViewCookieJar : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val header = try {
                CookieManager.getInstance().getCookie(url.toString())
            } catch (t: Throwable) {
                // WebView provider missing/updating: fetch without cookies (CDN images rarely need them).
                null
            }
            if (header.isNullOrBlank()) return emptyList()
            return header.split(';').mapNotNull { pair ->
                pair.trim().takeIf { it.isNotEmpty() }?.let { Cookie.parse(url, it) }
            }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(ImageFetchException(e.message ?: "Network error", e))
            }
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    companion object {
        const val MAX_BYTES = 12L * 1024 * 1024
        const val DEFAULT_REFERER = "https://m.jd.com/"
        private const val HTTP_CACHE_BYTES = 50L * 1024 * 1024

        @Volatile private var client: OkHttpClient? = null

        fun sharedClient(appContext: Context): OkHttpClient = client ?: synchronized(this) {
            client ?: OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .cookieJar(WebViewCookieJar)
                .retryOnConnectionFailure(true)
                .cache(Cache(File(appContext.cacheDir, "img-http"), HTTP_CACHE_BYTES))
                .build()
                .also { client = it }
        }

        /** Decodes "data:[mime][;base64],payload". */
        fun decodeDataUrl(url: String): ByteArray {
            val comma = url.indexOf(',')
            if (comma < 0) throw ImageFetchException("Malformed data URL")
            val meta = url.substring(5, comma)
            val payload = url.substring(comma + 1)
            if (meta.contains("svg", ignoreCase = true)) throw ImageFetchException("SVG is not supported")
            val bytes = try {
                if (meta.endsWith(";base64", ignoreCase = true)) Base64.decode(payload, Base64.DEFAULT)
                else URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.ISO_8859_1)
            } catch (e: IllegalArgumentException) {
                throw ImageFetchException("Malformed data URL", e)
            }
            if (bytes.size > MAX_BYTES) throw ImageFetchException("Image too large")
            if (bytes.isEmpty()) throw ImageFetchException("Empty image")
            return bytes
        }
    }
}

/**
 * Decodes image bytes into a software ARGB_8888 bitmap (needed for getPixels and OCR).
 * Uses ImageDecoder on API 28+ (AVIF on 31+, HEIF, animated formats' first frame) and falls
 * back to BitmapFactory. Very large images are subsampled to stay under [MAX_PIXELS].
 */
internal object ImageDecoding {
    private const val TAG = "JDT-OCR"
    const val MAX_PIXELS = 30_000_000L

    /** Returns null if the bytes are not a decodable image. May throw OutOfMemoryError. */
    fun decode(bytes: ByteArray): Bitmap? {
        var bmp: Bitmap? = null
        if (Build.VERSION.SDK_INT >= 28) {
            bmp = try {
                decodeWithImageDecoder(bytes)
            } catch (e: OutOfMemoryError) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "ImageDecoder failed: ${e.message}")
                null
            }
        }
        if (bmp == null) bmp = decodeWithBitmapFactory(bytes)
        return bmp?.let { ensureArgb8888(it) }
    }

    private fun decodeWithImageDecoder(bytes: ByteArray): Bitmap? {
        if (Build.VERSION.SDK_INT < 28) return null
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val px = info.size.width.toLong() * info.size.height.toLong()
            val ss = sampleSizeFor(px)
            if (ss > 1) decoder.setTargetSampleSize(ss)
        }
    }

    private fun decodeWithBitmapFactory(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                inSampleSize = sampleSizeFor(bounds.outWidth.toLong() * bounds.outHeight.toLong())
            }
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** Smallest power of two keeping the decoded pixel count under [MAX_PIXELS]. */
    fun sampleSizeFor(pixels: Long): Int {
        var ss = 1
        while (pixels / (ss.toLong() * ss) > MAX_PIXELS) ss *= 2
        return ss
    }

    private fun ensureArgb8888(b: Bitmap): Bitmap {
        if (b.config == Bitmap.Config.ARGB_8888) return b
        val copy = b.copy(Bitmap.Config.ARGB_8888, false)
        if (copy != null && copy !== b) b.recycle()
        return copy ?: b
    }
}
