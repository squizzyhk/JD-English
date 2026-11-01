package com.jdenglish.app.translate

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Free on-device Chinese <-> English translation (Google ML Kit). The zh model (~30 MB)
 * downloads once on first use; the English model is built in.
 */
class MlKitBackend : TranslationBackend {
    override val id: String = ID

    enum class ModelState { NOT_DOWNLOADED, DOWNLOADING, READY, FAILED }

    @Volatile var state: ModelState = ModelState.NOT_DOWNLOADED
        private set
    @Volatile var lastError: String? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobLock = Any()
    @Volatile private var downloadJob: Job? = null
    private val readyListeners = CopyOnWriteArraySet<() -> Unit>()
    @Volatile private var zhEn: Translator? = null
    @Volatile private var enZh: Translator? = null
    @Volatile private var lastFailAt = 0L
    private val parallel = Semaphore(PARALLELISM)

    /** Human-readable model status for the Settings screen. */
    fun statusText(): String = when (state) {
        ModelState.READY -> "Offline model ready"
        ModelState.DOWNLOADING -> "Downloading offline model…"
        ModelState.NOT_DOWNLOADED -> "Offline model not downloaded yet"
        ModelState.FAILED -> "Offline model unavailable" + (lastError?.let { ": $it" } ?: "")
    }

    /** Checks whether the model is already on disk (cheap; no download). */
    suspend fun refreshDownloadedState() {
        if (state == ModelState.READY || state == ModelState.DOWNLOADING) return
        try {
            val model = TranslateRemoteModel.Builder(TranslateLanguage.CHINESE).build()
            val ok = RemoteModelManager.getInstance().isModelDownloaded(model).await()
            if (ok && state != ModelState.DOWNLOADING) state = ModelState.READY
        } catch (t: Throwable) {
            Log.w(TAG, "isModelDownloaded failed", t)
        }
    }

    /** True when the translators exist and the model is on disk; never blocks. */
    val isReadyNow: Boolean get() = state == ModelState.READY && zhEn != null

    /** Called (on a background thread) each time the model becomes ready after a download/init. */
    fun addReadyListener(l: () -> Unit) { readyListeners.add(l) }
    fun removeReadyListener(l: () -> Unit) { readyListeners.remove(l) }

    /**
     * Ensures translators exist and the model is downloaded, waiting for the download if one
     * is needed. Returns false if unavailable. Only for callers that may wait (warm-up, the
     * explicit download button): the translate path never waits for a download (see [translate]).
     */
    suspend fun ensureReady(): Boolean {
        if (isReadyNow) return true
        startDownload()?.join()
        return isReadyNow
    }

    /**
     * Explicit user request ("Download offline model"): skips the failure back-off and starts
     * the download in the background if it isn't running. Non-blocking.
     */
    fun downloadNow() {
        lastFailAt = 0L
        startDownload()
    }

    /**
     * Starts (or joins) the single background download/init job. Returns null when the model is
     * ready or a recent failure is still backing off. Never blocks.
     */
    private fun startDownload(): Job? {
        if (isReadyNow) return null
        synchronized(jobLock) {
            downloadJob?.let { if (it.isActive) return it }
            if (isReadyNow) return null
            // Back off after a failure so a page full of strings doesn't hammer the download.
            if (state == ModelState.FAILED && System.currentTimeMillis() - lastFailAt < RETRY_AFTER_FAIL_MS) {
                return null
            }
            state = ModelState.DOWNLOADING
            return scope.launch { downloadAndInit() }.also { downloadJob = it }
        }
    }

    private suspend fun downloadAndInit() {
        try {
            val zhEnT = zhEn ?: Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.CHINESE)
                    .setTargetLanguage(TranslateLanguage.ENGLISH)
                    .build()
            ).also { zhEn = it }
            val conditions = DownloadConditions.Builder().build() // any network
            val done = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
                zhEnT.downloadModelIfNeeded(conditions).await()
                true
            }
            if (done != true) throw IllegalStateException("download timed out")
            if (enZh == null) {
                enZh = Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(TranslateLanguage.ENGLISH)
                        .setTargetLanguage(TranslateLanguage.CHINESE)
                        .build()
                )
            }
            state = ModelState.READY
            lastError = null
            Log.i(TAG, "ML Kit zh<->en model ready")
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) {
                state = ModelState.NOT_DOWNLOADED
                throw t
            }
            Log.w(TAG, "ML Kit model download failed", t)
            state = ModelState.FAILED
            lastFailAt = System.currentTimeMillis()
            lastError = describeError(t)
            return
        }
        for (l in readyListeners) {
            try { l() } catch (t: Throwable) { Log.w(TAG, "ready listener failed", t) }
        }
    }

    /**
     * Non-blocking readiness for the translate path. If the model isn't ready, starts the
     * download in the background and waits only briefly (enough for the model-already-on-disk
     * case); while a download is running it returns false at once, so a pending download never
     * stalls translation (callers get nulls and the page retries when the model is ready).
     */
    private suspend fun readyForTranslate(): Boolean {
        if (isReadyNow) return true
        val wasDownloading = state == ModelState.DOWNLOADING
        val job = startDownload() ?: return isReadyNow
        if (!wasDownloading) withTimeoutOrNull(QUICK_INIT_WAIT_MS) { job.join() }
        return isReadyNow
    }

    override suspend fun translate(items: List<TextItem>): List<String?> {
        if (items.isEmpty()) return emptyList()
        if (!readyForTranslate()) return items.map { null }
        val t = zhEn ?: return items.map { null }
        return coroutineScope {
            items.map { item ->
                async { parallel.withPermit { translateOne(t, item.text) } }
            }.awaitAll()
        }
    }

    override suspend fun translateToChinese(text: String): String? {
        if (text.isBlank()) return null
        if (!readyForTranslate()) return null
        val t = enZh ?: return null
        return try {
            parallel.withPermit { t.translate(text).await() }.trim().ifEmpty { null }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "en->zh failed", e); null
        }
    }

    /** Translates line by line so multi-line blocks keep their structure. */
    private suspend fun translateOne(t: Translator, text: String): String? = try {
        if ('\n' !in text) {
            t.translate(text).await().trim().ifEmpty { null }
        } else {
            val lines = text.split('\n')
            val out = lines.map { line ->
                if (Cjk.hasHan(line)) t.translate(line).await().trim() else line
            }
            out.joinToString("\n").trim().ifEmpty { null }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.w(TAG, "translate failed: ${e.message}")
        null
    }

    /** Releases native translator resources. The backend re-initializes lazily if used again. */
    fun close() {
        try { zhEn?.close() } catch (_: Throwable) {}
        try { enZh?.close() } catch (_: Throwable) {}
        zhEn = null
        enZh = null
        if (state == ModelState.READY) state = ModelState.NOT_DOWNLOADED
    }

    private fun describeError(t: Throwable): String {
        val msg = (t.message ?: t.javaClass.simpleName)
        val lower = msg.lowercase()
        val network = listOf("network", "timed out", "timeout", "unable to resolve", "connect", "host", "ssl", "download")
            .any { it in lower }
        return if (network) {
            "couldn't download the Chinese language pack from Google. Google services are blocked in mainland China — " +
                "connect a VPN once to download it (about 30 MB), or switch the engine to Claude in Settings."
        } else msg
    }

    companion object {
        const val ID = "mlkit"
        private const val TAG = "JDT.MlKit"
        private const val PARALLELISM = 4
        private const val DOWNLOAD_TIMEOUT_MS = 5 * 60_000L
        private const val RETRY_AFTER_FAIL_MS = 30_000L
        private const val QUICK_INIT_WAIT_MS = 2_000L
    }
}

/** Awaits a Play Services Task without kotlinx-coroutines-play-services. */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont: CancellableContinuation<T> ->
    addOnSuccessListener { r -> if (cont.isActive) cont.resume(r) }
    addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
    addOnCanceledListener { cont.cancel() }
}
