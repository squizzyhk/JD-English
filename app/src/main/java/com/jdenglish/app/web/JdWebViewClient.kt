package com.jdenglish.app.web

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import java.net.URISyntaxException

/**
 * Keeps all web navigation in-app, blocks app-launch schemes, routes tel:/mailto:/sms:
 * to the system, and reports page lifecycle to the activity.
 */
class JdWebViewClient(
    private val injector: ScriptInjector,
    private val callbacks: Callbacks,
) : WebViewClient() {

    interface Callbacks {
        fun onPageStarted(url: String?)
        fun onPageFinished(url: String?)
        fun onHistoryChanged(url: String?)
        fun onMainFrameError(errorCode: Int, description: String?, failingUrl: String?)
        fun onRendererGone(view: WebView)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        handleUrl(view, request.url)

    private fun handleUrl(view: WebView, uri: Uri?): Boolean {
        uri ?: return true
        val scheme = uri.scheme?.lowercase() ?: return true
        return when (scheme) {
            "http", "https" -> false // every web host stays in-app (JD login uses many domains)
            "about", "data", "blob", "javascript" -> false
            "tel" -> { launch(view, Intent(Intent.ACTION_DIAL, uri)); true }
            "mailto", "sms", "smsto" -> { launch(view, Intent(Intent.ACTION_SENDTO, uri)); true }
            "intent" -> { handleIntentScheme(view, uri.toString()); true }
            else -> {
                if (scheme in JdUrls.BLOCKED_SCHEMES) Log.d(TAG, "Blocked app-launch link: $scheme")
                else Log.d(TAG, "Ignored unsupported scheme: $scheme")
                true
            }
        }
    }

    /** intent://... links: never launch another app, but honour an http(s) browser fallback. */
    private fun handleIntentScheme(view: WebView, url: String) {
        val fallback = try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
        } catch (e: URISyntaxException) {
            null
        } catch (e: RuntimeException) {
            null
        }
        if (fallback != null) {
            val s = Uri.parse(fallback).scheme?.lowercase()
            if (s == "http" || s == "https") {
                view.post { view.loadUrl(fallback) }
                return
            }
        }
        Log.d(TAG, "Blocked intent: link without web fallback")
    }

    private fun launch(view: WebView, intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            view.context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.d(TAG, "No activity for ${intent.data}")
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to open ${intent.data}", e)
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        callbacks.onPageStarted(url)
        injector.onPageStarted(view)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        injector.onPageFinished(view)
        callbacks.onPageFinished(url)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        callbacks.onHistoryChanged(url)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        super.onReceivedError(view, request, error)
        if (request.isForMainFrame) {
            callbacks.onMainFrameError(error.errorCode, error.description?.toString(), request.url?.toString())
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
        Log.e(TAG, "Renderer gone (crash=${detail?.didCrash()})")
        callbacks.onRendererGone(view)
        return true
    }

    companion object {
        private const val TAG = "JDT.Client"
    }
}
