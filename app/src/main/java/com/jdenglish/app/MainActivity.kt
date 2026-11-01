package com.jdenglish.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.jdenglish.app.bridge.TranslatorBridge
import com.jdenglish.app.translate.Cjk
import com.jdenglish.app.translate.Settings
import com.jdenglish.app.translate.TranslationEngine
import com.jdenglish.app.web.JdChromeClient
import com.jdenglish.app.web.JdUrls
import com.jdenglish.app.web.JdWebView
import com.jdenglish.app.web.JdWebViewClient
import com.jdenglish.app.web.ScriptInjector
import com.jdenglish.app.web.WebViewSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : AppCompatActivity(),
    JdWebViewClient.Callbacks,
    JdChromeClient.Callbacks,
    TranslatorBridge.Listener {

    private lateinit var settings: Settings
    private lateinit var toolbar: MaterialToolbar
    private lateinit var progress: LinearProgressIndicator
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var webView: JdWebView
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var searchBar: View
    private lateinit var searchInput: EditText
    private lateinit var searchClear: ImageButton
    private lateinit var errorView: View
    private lateinit var bridge: TranslatorBridge
    private lateinit var injector: ScriptInjector

    private val uiScope = MainScope()
    private var searchJob: Job? = null
    private var langToggle: TextView? = null

    private var translatedTitle: String? = null
    private var rawTitle: String? = null
    private var searchForced = false
    private var syncingNav = false
    private var failedUrl: String? = null
    private var lastEnabled = true
    private var lastImages = true
    private var lastEngine = "mlkit"
    private var lastClaudeKey = ""
    private var lastClaudeModel = ""
    /** Offline model became ready (background thread): re-request what failed meanwhile. */
    private val modelReadyListener: () -> Unit = { if (::bridge.isInitialized) bridge.retryFailed() }
    private var rendererGone = false

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooser: ActivityResultLauncher<Intent>

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = handleBack()
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        settings = Settings(this)

        toolbar = findViewById(R.id.toolbar)
        progress = findViewById(R.id.progress)
        swipe = findViewById(R.id.swipe_refresh)
        webView = findViewById(R.id.web_view)
        bottomNav = findViewById(R.id.bottom_nav)
        searchBar = findViewById(R.id.search_bar)
        searchInput = findViewById(R.id.search_input)
        searchClear = findViewById(R.id.search_clear)
        errorView = findViewById(R.id.error_view)

        fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val cb = fileCallback
            fileCallback = null
            cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        }

        setupInsets()
        setupToolbar()
        setupSearch()
        setupBottomNav()
        setupErrorView()
        setupWebView()
        onBackPressedDispatcher.addCallback(this, backCallback)

        lastEnabled = settings.enabled
        lastImages = settings.images
        lastEngine = settings.engine
        lastClaudeKey = settings.claudeKey
        lastClaudeModel = settings.claudeModel
        TranslationEngine.get(this).mlKit.addReadyListener(modelReadyListener)

        val restored = savedInstanceState?.let { webView.restoreState(it) } != null &&
            !webView.url.isNullOrEmpty()
        if (!restored) {
            val start = urlFromIntent(intent) ?: JdUrls.HOME
            webView.loadUrl(start)
        }
        updateChrome(webView.url ?: JdUrls.HOME)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        urlFromIntent(intent)?.let { load(it) }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
        applySettingsChanges()
        if (reloadRequested) {
            reloadRequested = false
            webView.loadUrl(JdUrls.HOME)
        }
    }

    override fun onPause() {
        webView.onPause()
        webView.pauseTimers()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!rendererGone) webView.saveState(outState)
    }

    override fun onDestroy() {
        searchJob?.cancel()
        uiScope.cancel()
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        TranslationEngine.get(this).mlKit.removeReadyListener(modelReadyListener)
        if (::bridge.isInitialized) bridge.destroy()
        if (::injector.isInitialized) injector.release()
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.removeJavascriptInterface(TranslatorBridge.JS_NAME)
        webView.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ setup

    private fun setupInsets() {
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime()) && ime.bottom > bars.bottom
            v.setPadding(bars.left, bars.top, bars.right, if (imeVisible) ime.bottom else 0)
            bottomNav.isVisible = !imeVisible
            bottomNav.setPadding(0, 0, 0, if (imeVisible) 0 else bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupToolbar() {
        toolbar.inflateMenu(R.menu.main)
        toolbar.setNavigationContentDescription(R.string.action_back)
        toolbar.setNavigationOnClickListener { if (webView.canGoBack()) webView.goBack() }
        toolbar.setOnMenuItemClickListener { onMenuItem(it) }
        val langItem = toolbar.menu.findItem(R.id.action_lang)
        langToggle = langItem.actionView?.findViewById(R.id.lang_toggle)
        langToggle?.setOnClickListener { toggleTranslation() }
        updateLangToggle()
    }

    private fun setupSearch() {
        searchInput.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO || enter) {
                submitSearch(); true
            } else false
        }
        searchInput.doAfterTextChanged { searchClear.isVisible = !it.isNullOrEmpty() }
        searchInput.setOnFocusChangeListener { _, _ -> updateBackCallback() }
        searchClear.setOnClickListener { searchInput.text = null; searchInput.requestFocus() }
        findViewById<Button>(R.id.search_go).setOnClickListener { submitSearch() }
    }

    private fun setupBottomNav() {
        bottomNav.setOnItemSelectedListener { item ->
            if (!syncingNav) tabUrl(item.itemId)?.let { load(it) }
            true
        }
        bottomNav.setOnItemReselectedListener { item ->
            tabUrl(item.itemId)?.let { load(it) }
        }
    }

    private fun setupErrorView() {
        findViewById<Button>(R.id.error_retry).setOnClickListener {
            val url = failedUrl ?: webView.url ?: JdUrls.HOME
            errorView.isVisible = false
            if (webView.url == url) webView.reload() else webView.loadUrl(url)
        }
        findViewById<Button>(R.id.error_home).setOnClickListener {
            errorView.isVisible = false
            webView.loadUrl(JdUrls.HOME)
        }
    }

    @SuppressLint("JavascriptInterface")
    private fun setupWebView() {
        val ua = WebViewSetup.configure(webView, BuildConfig.DEBUG)
        injector = ScriptInjector(this)
        bridge = TranslatorBridge(this, webView, this)
        bridge.setUserAgent(ua)
        webView.addJavascriptInterface(bridge, TranslatorBridge.JS_NAME)
        injector.install(webView)
        webView.webViewClient = JdWebViewClient(injector, this)
        webView.webChromeClient = JdChromeClient(this, uiScope, this)
        webView.setDownloadListener { url, _, _, _, _ -> openExternally(url) }

        swipe.setColorSchemeResources(R.color.jd_red)
        swipe.setOnChildScrollUpCallback { _, _ -> !webView.isAtTopForRefresh() }
        swipe.setOnRefreshListener {
            errorView.isVisible = false
            webView.reload()
        }
    }

    // ------------------------------------------------------------------ WebViewClient callbacks

    override fun onPageStarted(url: String?) {
        bridge.onPageStarted(url)
        webView.resetScrollTracking()
        translatedTitle = null
        rawTitle = null
        errorView.isVisible = false
        failedUrl = null
        progress.isVisible = true
        updateChrome(url)
    }

    override fun onPageFinished(url: String?) {
        swipe.isRefreshing = false
        progress.isVisible = false
        updateChrome(url)
    }

    override fun onHistoryChanged(url: String?) {
        bridge.setPageUrl(url)
        updateChrome(url)
    }

    override fun onMainFrameError(errorCode: Int, description: String?, failingUrl: String?) {
        failedUrl = failingUrl
        swipe.isRefreshing = false
        progress.isVisible = false
        val (title, msg) = when (errorCode) {
            WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_CONNECT ->
                R.string.error_title_offline to R.string.error_msg_offline
            WebViewClient.ERROR_TIMEOUT ->
                R.string.error_title_timeout to R.string.error_msg_timeout
            else -> R.string.error_title_generic to R.string.error_msg_generic
        }
        errorView.findViewById<TextView>(R.id.error_title).setText(title)
        errorView.findViewById<TextView>(R.id.error_message).setText(msg)
        val details = errorView.findViewById<TextView>(R.id.error_details)
        val d = description?.takeIf { it.isNotBlank() }
        details.isVisible = d != null
        details.text = d?.let { getString(R.string.error_details, it) }
        errorView.isVisible = true
        updateBackCallback()
    }

    override fun onRendererGone(view: WebView) {
        Toast.makeText(this, R.string.page_crashed, Toast.LENGTH_LONG).show()
        // The WebView is unusable now; rebuild the activity (and its WebView) from scratch.
        rendererGone = true
        val url = view.url
        if (url != null) intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setClass(this, MainActivity::class.java)
        recreate()
    }

    // ------------------------------------------------------------------ WebChromeClient callbacks

    override fun onProgress(progress: Int) {
        if (searchJob?.isActive == true) return
        setProgressIndeterminate(false)
        this.progress.setProgressCompat(progress, true)
        this.progress.isVisible = progress in 1..99
    }

    override fun onTitle(title: String?) {
        rawTitle = title?.trim()
        refreshTitle()
    }

    override fun onShowFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        return try {
            fileChooser.launch(params.createIntent())
            true
        } catch (e: ActivityNotFoundException) {
            fileCallback = null
            Toast.makeText(this, R.string.no_file_picker, Toast.LENGTH_SHORT).show()
            false
        }
    }

    // ------------------------------------------------------------------ bridge callbacks

    override fun onTranslatedTitle(title: String) {
        translatedTitle = title
        refreshTitle()
    }

    // ------------------------------------------------------------------ title / chrome state

    private fun refreshTitle() {
        val raw = rawTitle?.takeIf { it.isNotBlank() && !looksLikeUrl(it) }
        val title = when {
            settings.enabled && !translatedTitle.isNullOrBlank() -> translatedTitle
            raw != null && (!settings.enabled || !Cjk.hasHan(raw)) -> raw
            else -> null
        }
        toolbar.title = title ?: getString(R.string.app_name)
    }

    private fun looksLikeUrl(s: String) = s.startsWith("http://") || s.startsWith("https://") || s.contains("jd.com/")

    private fun updateChrome(url: String?) {
        val canGoBack = webView.canGoBack()
        if (canGoBack) {
            if (toolbar.navigationIcon == null) toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        } else {
            toolbar.navigationIcon = null
        }
        val tab = JdUrls.tabFor(url)
        if (tab != null) {
            val id = when (tab) {
                JdUrls.Tab.HOME -> R.id.nav_home
                JdUrls.Tab.CATEGORIES -> R.id.nav_categories
                JdUrls.Tab.CART -> R.id.nav_cart
                JdUrls.Tab.MY_JD -> R.id.nav_my_jd
            }
            if (bottomNav.selectedItemId != id) {
                syncingNav = true
                bottomNav.selectedItemId = id
                syncingNav = false
            }
        }
        val onHome = JdUrls.isHome(url)
        if (!onHome && !searchInput.hasFocus()) searchForced = false
        searchBar.isVisible = onHome || searchForced
        refreshTitle()
        updateBackCallback()
    }

    private fun updateBackCallback() {
        backCallback.isEnabled = webView.canGoBack() || errorView.isVisible ||
            (searchForced && searchBar.isVisible) || (::searchInput.isInitialized && searchInput.hasFocus())
    }

    private fun handleBack() {
        when {
            searchInput.hasFocus() -> {
                hideKeyboard()
                searchInput.clearFocus()
                if (searchForced && !JdUrls.isHome(webView.url)) {
                    searchForced = false
                    searchBar.isVisible = false
                }
            }
            searchForced && searchBar.isVisible && !JdUrls.isHome(webView.url) -> {
                searchForced = false
                searchBar.isVisible = false
            }
            webView.canGoBack() -> {
                errorView.isVisible = false
                webView.goBack()
            }
            errorView.isVisible -> errorView.isVisible = false
            else -> {
                // Nothing left to handle: let the system finish the activity.
                backCallback.isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                return
            }
        }
        updateBackCallback()
    }

    // ------------------------------------------------------------------ menu

    private fun onMenuItem(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_search -> toggleSearchBar()
            R.id.action_lang -> toggleTranslation()
            R.id.action_refresh -> { errorView.isVisible = false; webView.reload() }
            R.id.action_copy_link -> copyLink()
            R.id.action_share -> shareLink()
            R.id.action_open_browser -> webView.url?.let { openExternally(it) }
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            else -> return false
        }
        return true
    }

    private fun toggleSearchBar() {
        if (searchBar.isVisible && !JdUrls.isHome(webView.url)) {
            searchForced = false
            hideKeyboard()
            searchInput.clearFocus()
            searchBar.isVisible = false
        } else {
            searchForced = true
            searchBar.isVisible = true
            searchInput.requestFocus()
            showKeyboard(searchInput)
        }
        updateBackCallback()
    }

    private fun toggleTranslation() {
        val enabled = !settings.enabled
        settings.enabled = enabled
        lastEnabled = enabled
        webView.evaluateJavascript("window.__jdt&&window.__jdt.setEnabled($enabled)", null)
        updateLangToggle()
        refreshTitle()
        Toast.makeText(
            this,
            if (enabled) R.string.toast_showing_english else R.string.toast_showing_original,
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun updateLangToggle() {
        val enabled = settings.enabled
        langToggle?.apply {
            setText(if (enabled) R.string.lang_toggle_en else R.string.lang_toggle_zh)
            val desc = getString(if (enabled) R.string.lang_toggle_desc_on else R.string.lang_toggle_desc_off)
            contentDescription = desc
            TooltipCompat.setTooltipText(this, desc)
        }
    }

    /** Push changes made in Settings to the live page. */
    private fun applySettingsChanges() {
        val enabled = settings.enabled
        val images = settings.images
        val engine = settings.engine
        val claudeKey = settings.claudeKey
        val claudeModel = settings.claudeModel
        if (enabled != lastEnabled) {
            webView.evaluateJavascript("window.__jdt&&window.__jdt.setEnabled($enabled)", null)
        }
        if (images != lastImages) {
            webView.evaluateJavascript("window.__jdt&&window.__jdt.setImages($images)", null)
        }
        val translatorChanged = engine != lastEngine ||
            (engine == "claude" && (claudeKey != lastClaudeKey || claudeModel != lastClaudeModel))
        if (translatorChanged && enabled) {
            // Strings/images that failed (cached as null for this page view) get another chance.
            webView.evaluateJavascript(TranslatorBridge.RETRY_JS, null)
        }
        lastEnabled = enabled
        lastImages = images
        lastEngine = engine
        lastClaudeKey = claudeKey
        lastClaudeModel = claudeModel
        updateLangToggle()
        refreshTitle()
    }

    private fun copyLink() {
        val url = webView.url ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(rawTitle ?: url, url))
        // Android 13+ shows its own clipboard confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareLink() {
        val url = webView.url ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            (translatedTitle ?: rawTitle)?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        val chooser = Intent.createChooser(send, getString(R.string.share_via))
        chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, MainActivity::class.java)))
        startSafely(chooser)
    }

    /** Open a URL in another app (browser), never in ourselves. */
    private fun openExternally(url: String) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        val chooser = Intent.createChooser(view, getString(R.string.choose_browser))
        chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, MainActivity::class.java)))
        startSafely(chooser)
    }

    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ search

    private fun submitSearch() {
        val query = searchInput.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) return
        hideKeyboard()
        searchInput.clearFocus()
        searchJob?.cancel()
        val needsTranslation = !Cjk.hasHan(query) && query.any { it.isLetter() }
        if (!needsTranslation) {
            runSearch(query)
            return
        }
        setProgressIndeterminate(true)
        progress.isVisible = true
        searchInput.isEnabled = false
        val engine = TranslationEngine.get(this)
        val pending = uiScope.async(Dispatchers.IO) {
            try {
                engine.toChinese(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Query translation failed", e)
                null
            }
        }
        searchJob = uiScope.launch {
            try {
                // Fall back to the query as typed if translation is slow or unavailable.
                val zh = withTimeoutOrNull(SEARCH_TRANSLATE_TIMEOUT_MS) { pending.await() }
                runSearch(zh?.trim()?.takeIf { it.isNotEmpty() } ?: query)
            } finally {
                setProgressIndeterminate(false)
                progress.isVisible = false
                searchInput.isEnabled = true
            }
        }
    }

    /** LinearProgressIndicator must be hidden while switching modes. */
    private fun setProgressIndeterminate(indeterminate: Boolean) {
        if (progress.isIndeterminate == indeterminate) return
        val wasVisible = progress.isVisible
        progress.visibility = View.INVISIBLE
        progress.isIndeterminate = indeterminate
        progress.isVisible = wasVisible
    }

    private fun runSearch(keyword: String) {
        if (!JdUrls.isHome(webView.url)) searchForced = false
        load(JdUrls.search(keyword))
    }

    private fun showKeyboard(view: View) {
        view.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    // ------------------------------------------------------------------ navigation helpers

    private fun load(url: String) {
        errorView.isVisible = false
        webView.loadUrl(url)
    }

    private fun tabUrl(itemId: Int): String? = when (itemId) {
        R.id.nav_home -> JdUrls.HOME
        R.id.nav_categories -> JdUrls.CATEGORIES
        R.id.nav_cart -> JdUrls.CART
        R.id.nav_my_jd -> JdUrls.MY_JD
        else -> null
    }

    /** Extract a JD URL from a VIEW deep link or shared text; null if none. */
    private fun urlFromIntent(intent: Intent?): String? {
        intent ?: return null
        val url = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.takeIf { JdUrls.isJdUrl(it) }
            Intent.ACTION_SEND -> JdUrls.extractJdUrl(
                listOfNotNull(
                    intent.getStringExtra(Intent.EXTRA_TEXT),
                    intent.getStringExtra(Intent.EXTRA_SUBJECT),
                ).joinToString(" "),
            )
            else -> null
        }
        return url?.let { JdUrls.toMobile(it) }
    }

    companion object {
        private const val TAG = "JDT.Main"
        private const val SEARCH_TRANSLATE_TIMEOUT_MS = 5000L

        @Volatile private var reloadRequested = false

        /** Settings asks for a fresh home page (e.g. after signing out). */
        fun requestReloadOnResume() {
            reloadRequested = true
        }
    }
}
