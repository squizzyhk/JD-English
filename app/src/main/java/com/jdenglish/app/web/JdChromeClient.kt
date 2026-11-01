package com.jdenglish.app.web

import android.app.Activity
import android.content.DialogInterface
import android.net.Uri
import android.text.InputType
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jdenglish.app.R
import com.jdenglish.app.translate.Cjk
import com.jdenglish.app.translate.Settings
import com.jdenglish.app.translate.TextItem
import com.jdenglish.app.translate.TranslationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Progress, titles, file chooser (JD review photo upload), geolocation (denied), and
 * JS alert/confirm/prompt shown as native dialogs with the message translated.
 */
class JdChromeClient(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val callbacks: Callbacks,
) : WebChromeClient() {

    interface Callbacks {
        fun onProgress(progress: Int)
        fun onTitle(title: String?)
        /** Launch a picker; return false if no picker could be started. */
        fun onShowFileChooser(callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean
    }

    private val settings = Settings(activity)

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        callbacks.onProgress(newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        callbacks.onTitle(title)
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean = callbacks.onShowFileChooser(filePathCallback, fileChooserParams)

    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
        // Deny by default; JD falls back to manual address selection.
        callback.invoke(origin, false, false)
    }

    override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
        showDialog(url, message, result, Kind.ALERT, null)
        return true
    }

    override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
        showDialog(url, message, result, Kind.CONFIRM, null)
        return true
    }

    override fun onJsPrompt(
        view: WebView,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult,
    ): Boolean {
        showDialog(url, message, result, Kind.PROMPT, defaultValue)
        return true
    }

    private enum class Kind { ALERT, CONFIRM, PROMPT }

    private fun showDialog(url: String?, message: String?, result: JsResult, kind: Kind, defaultValue: String?) {
        if (activity.isFinishing || activity.isDestroyed) {
            result.cancel()
            return
        }
        val original = message.orEmpty()
        val needsTranslation = settings.enabled && Cjk.hasHan(original)
        if (!needsTranslation) {
            buildDialog(url, original, result, kind, defaultValue)
            return
        }
        val engine = TranslationEngine.get(activity)
        // Translate off the main thread; never make the page wait more than the timeout.
        val pending = scope.async(Dispatchers.IO) {
            try {
                engine.translate(listOf(TextItem(original, "ui"))).firstOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        scope.launch {
            var shown = false
            try {
                val translated = withTimeoutOrNull(DIALOG_TRANSLATE_TIMEOUT_MS) { pending.await() }
                if (!activity.isFinishing && !activity.isDestroyed) {
                    buildDialog(url, translated?.takeIf { it.isNotBlank() } ?: original, result, kind, defaultValue)
                    shown = true
                }
            } finally {
                if (!shown) result.cancel()
            }
        }
    }

    private fun buildDialog(url: String?, message: String, result: JsResult, kind: Kind, defaultValue: String?) {
        var handled = false
        fun confirm(value: String? = null) {
            if (handled) return
            handled = true
            if (result is JsPromptResult && kind == Kind.PROMPT) result.confirm(value) else result.confirm()
        }
        fun cancel() {
            if (handled) return
            handled = true
            result.cancel()
        }

        val host = JdUrls.hostOf(url)
        val title = if (host.isNullOrEmpty()) activity.getString(R.string.dialog_title_page_says)
        else activity.getString(R.string.dialog_title_site_says, host)

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)

        var input: EditText? = null
        if (kind == Kind.PROMPT) {
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            val edit = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                setText(defaultValue.orEmpty())
                setSelection(text.length)
            }
            input = edit
            val container = FrameLayout(activity).apply {
                setPadding(pad, 0, pad, 0)
                addView(edit, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            builder.setView(container)
        }

        builder.setPositiveButton(R.string.ok) { _, _ -> confirm(input?.text?.toString()) }
        if (kind == Kind.ALERT) {
            // Dismissing an alert counts as acknowledging it.
            builder.setOnCancelListener { confirm() }
        } else {
            builder.setNegativeButton(R.string.cancel) { _, _ -> cancel() }
            builder.setOnCancelListener { cancel() }
        }
        val dialog = builder.create()
        dialog.setOnDismissListener(DialogInterface.OnDismissListener {
            if (kind == Kind.ALERT) confirm() else cancel()
        })
        dialog.show()
    }

    companion object {
        private const val DIALOG_TRANSLATE_TIMEOUT_MS = 1500L
    }
}
