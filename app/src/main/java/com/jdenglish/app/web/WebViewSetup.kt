package com.jdenglish.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/** One-time configuration of the app's WebView. */
object WebViewSetup {
    private const val TAG = "JDT.WebView"

    /** Chrome-like mobile UA used if the system default UA cannot be read. */
    const val FALLBACK_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Mobile Safari/537.36"

    private val WV_TOKEN = Regex(";\\s*wv(?=[;)])")
    private val VERSION_TOKEN = Regex("Version/\\d+(\\.\\d+)*\\s+")

    /**
     * Turns the WebView UA into a plain mobile Chrome UA by removing the "; wv" token
     * and the "Version/4.0 " token, so JD serves its regular H5 site instead of
     * in-app/"open in JD app" variants.
     */
    fun cleanUserAgent(ua: String): String =
        ua.replace(WV_TOKEN, "").replace(VERSION_TOKEN, "").replace(Regex("\\s{2,}"), " ").trim()

    fun defaultUserAgent(context: Context): String =
        try {
            cleanUserAgent(WebSettings.getDefaultUserAgent(context))
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read default UA", t)
            FALLBACK_UA
        }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    fun configure(webView: WebView, debuggable: Boolean): String {
        if (debuggable) WebView.setWebContentsDebuggingEnabled(true)

        val ua = defaultUserAgent(webView.context)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            allowFileAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = ua
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            // JD's site is designed light-only; auto-darkening breaks product imagery.
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = false
        return ua
    }
}
