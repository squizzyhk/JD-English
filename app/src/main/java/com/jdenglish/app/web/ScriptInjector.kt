package com.jdenglish.app.web

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Injects the content script (assets/js/jdt.js).
 *
 * Preferred: document-start injection in every frame via
 * [WebViewCompat.addDocumentStartJavaScript]. Fallback for old WebView builds:
 * evaluate the script in onPageStarted and again in onPageFinished (the script is
 * idempotent; it guards itself with `window.__jdt`).
 */
class ScriptInjector(context: Context) {

    private val script: String? = loadScript(context.applicationContext)
    private var handler: ScriptHandler? = null

    /** True once the script is registered as a document-start script. */
    var usesDocumentStart: Boolean = false
        private set

    val hasScript: Boolean get() = script != null

    /** Must be called before the first loadUrl. */
    fun install(webView: WebView) {
        val js = script ?: return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                handler = WebViewCompat.addDocumentStartJavaScript(webView, js, setOf("*"))
                usesDocumentStart = true
            } catch (t: Throwable) {
                Log.w(TAG, "addDocumentStartJavaScript failed; using fallback injection", t)
                usesDocumentStart = false
            }
        }
    }

    fun onPageStarted(webView: WebView) {
        if (!usesDocumentStart) inject(webView)
    }

    fun onPageFinished(webView: WebView) {
        if (!usesDocumentStart) inject(webView)
        webView.evaluateJavascript(RESCAN, null)
    }

    private fun inject(webView: WebView) {
        val js = script ?: return
        webView.evaluateJavascript(js, null)
    }

    fun release() {
        if (handler != null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try { handler?.remove() } catch (_: Throwable) {}
        }
        handler = null
    }

    companion object {
        private const val TAG = "JDT.Inject"
        const val ASSET_PATH = "js/jdt.js"
        const val RESCAN = "window.__jdt&&window.__jdt.rescan()"

        @Volatile private var cached: String? = null

        private fun loadScript(context: Context): String? {
            cached?.let { return it }
            return try {
                context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    .also { cached = it }
            } catch (t: Throwable) {
                Log.e(TAG, "Content script $ASSET_PATH missing; pages will not be translated", t)
                null
            }
        }
    }
}
