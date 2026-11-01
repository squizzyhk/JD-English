package com.jdenglish.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebViewDatabase
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jdenglish.app.translate.Settings
import com.jdenglish.app.translate.TranslationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_settings)
        val root = findViewById<android.view.View>(R.id.settings_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        findViewById<MaterialToolbar>(R.id.settings_toolbar).setNavigationOnClickListener { finish() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private val scope = MainScope()
        private val handler = Handler(Looper.getMainLooper())
        private val statusTicker = object : Runnable {
            override fun run() {
                updateStatus()
                handler.postDelayed(this, STATUS_REFRESH_MS)
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            val ctx = requireContext()
            val engine = TranslationEngine.get(ctx)

            val engineList = findPreference<ListPreference>(Settings.KEY_ENGINE)
            val apiKey = findPreference<EditTextPreference>(Settings.KEY_CLAUDE_KEY)
            val model = findPreference<EditTextPreference>(Settings.KEY_CLAUDE_MODEL)

            fun updateClaudeVisibility(engineId: String?) {
                val claude = engineId == "claude"
                apiKey?.isVisible = claude
                model?.isVisible = claude
            }
            updateClaudeVisibility(engineList?.value)
            engineList?.setOnPreferenceChangeListener { _, newValue ->
                updateClaudeVisibility(newValue as? String)
                handler.post {
                    if (newValue == "mlkit") engine.downloadModel()
                    updateStatus()
                }
                true
            }

            apiKey?.apply {
                setOnBindEditTextListener { edit ->
                    edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    edit.isSingleLine = true
                    edit.setSelection(edit.text.length)
                }
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                    val key = p.text?.trim().orEmpty()
                    if (key.isEmpty()) getString(R.string.pref_claude_key_not_set)
                    else getString(R.string.pref_claude_key_set, mask(key))
                }
                setOnPreferenceChangeListener { pref, newValue ->
                    val trimmed = (newValue as? String)?.trim().orEmpty()
                    if (trimmed != newValue) {
                        (pref as EditTextPreference).text = trimmed
                        false
                    } else {
                        true
                    }
                }
            }

            model?.apply {
                setOnBindEditTextListener { edit ->
                    edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    edit.isSingleLine = true
                }
                setOnPreferenceChangeListener { pref, newValue ->
                    val v = (newValue as? String)?.trim().orEmpty()
                    if (v.isEmpty() || v != newValue) {
                        (pref as EditTextPreference).text = v.ifEmpty { Settings.DEFAULT_CLAUDE_MODEL }
                        false
                    } else {
                        true
                    }
                }
            }

            findPreference<Preference>("download_model")?.setOnPreferenceClickListener {
                engine.downloadModel()
                Toast.makeText(ctx, R.string.toast_download_started, Toast.LENGTH_SHORT).show()
                updateStatus()
                true
            }

            findPreference<Preference>("clear_cache")?.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.pref_clear_cache_confirm)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.clear) { _, _ ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                try { engine.clearCache() } catch (_: Exception) {}
                            }
                            context?.let { Toast.makeText(it, R.string.toast_cache_cleared, Toast.LENGTH_SHORT).show() }
                        }
                    }
                    .show()
                true
            }

            findPreference<Preference>("clear_cookies")?.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.pref_clear_cookies_confirm)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.sign_out) { _, _ -> clearCookies() }
                    .show()
                true
            }

            findPreference<Preference>("version")?.summary = BuildConfig.VERSION_NAME
        }

        private fun clearCookies() {
            val ctx = context ?: return
            val cookies = CookieManager.getInstance()
            cookies.removeAllCookies {
                cookies.flush()
            }
            WebStorage.getInstance().deleteAllData()
            try {
                @Suppress("DEPRECATION")
                WebViewDatabase.getInstance(ctx).clearFormData()
                WebViewDatabase.getInstance(ctx).clearHttpAuthUsernamePassword()
            } catch (_: Exception) {}
            Toast.makeText(ctx, R.string.toast_cookies_cleared, Toast.LENGTH_SHORT).show()
            MainActivity.requestReloadOnResume()
        }

        private fun updateStatus() {
            val ctx = context ?: return
            val pref = findPreference<Preference>("engine_status") ?: return
            val s = try { TranslationEngine.get(ctx).status() } catch (_: Exception) { "" }
            pref.summary = s.ifBlank { getString(R.string.pref_status_unknown) }
        }

        override fun onResume() {
            super.onResume()
            handler.post(statusTicker)
        }

        override fun onPause() {
            handler.removeCallbacks(statusTicker)
            super.onPause()
        }

        override fun onDestroy() {
            scope.cancel()
            handler.removeCallbacksAndMessages(null)
            super.onDestroy()
        }

        private fun mask(key: String): String =
            if (key.length <= 8) "••••" else "${key.take(7)}…${key.takeLast(4)}"

        companion object {
            private const val STATUS_REFRESH_MS = 1000L
        }
    }
}
