package com.ola.browser

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Patterns
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.URLEncoder

/**
 * Main Activity for Ola Air Browser
 *
 * Ultra-lightweight browser tailored for Android 10 (API 29) on a 7-inch 800x480 landscape display.
 * Designed with high-contrast cockpit ergonomics, 5-tab RAM optimization, host-based ad blocking,
 * and zero-tracking 24-hour auto-expiring history.
 */
class MainActivity : AppCompatActivity() {

    // Helper managers
    private lateinit var adBlocker: AdBlocker
    private lateinit var bookmarkManager: BookmarkManager
    private lateinit var historyManager: HistoryManager
    private lateinit var tabManager: TabManager

    // UI elements
    private lateinit var navBar: LinearLayout
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnReload: ImageButton
    private lateinit var btnBookmarkStar: ImageButton
    private lateinit var etUrl: EditText
    private lateinit var btnAdBlock: ImageButton
    private lateinit var btnBookmarks: ImageButton
    private lateinit var btnHistory: ImageButton

    private lateinit var tabLayoutContainer: LinearLayout
    private lateinit var btnNewTab: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var webContainer: FrameLayout
    private lateinit var quickDashboard: LinearLayout

    // State
    private var isLoadingPage: Boolean = false

    // Periodic 24-hour purge timer (runs every 15 minutes)
    private val purgeHandler = Handler(Looper.getMainLooper())
    private val purgeRunnable = object : Runnable {
        override fun run() {
            historyManager.purgeExpired()
            purgeHandler.postDelayed(this, PURGE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize managers
        adBlocker = AdBlocker(this)
        bookmarkManager = BookmarkManager(this)
        historyManager = HistoryManager(this)

        // Initial purge of any history entries older than 24 hours
        historyManager.purgeExpired()

        initViews()
        setupTabManager()
        setupListeners()
        setupQuickDashboard()

        // Start periodic auto-purge
        purgeHandler.postDelayed(purgeRunnable, PURGE_INTERVAL_MS)

        // Handle URL from external intent if opened via ACTION_VIEW
        val intentUrl = intent?.dataString
        val startUrl = if (!intentUrl.isNullOrBlank()) intentUrl else DEFAULT_SEARCH_URL
        tabManager.initFirstTab(startUrl)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val intentUrl = intent?.dataString
        if (!intentUrl.isNullOrBlank()) {
            if (tabManager.canOpenNewTab) {
                tabManager.openNewTab(intentUrl)
            } else {
                tabManager.getActiveTab()?.webView?.loadUrl(intentUrl)
            }
        }
    }

    private fun initViews() {
        navBar = findViewById(R.id.navBar)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        btnReload = findViewById(R.id.btnReload)
        btnBookmarkStar = findViewById(R.id.btnBookmarkStar)
        etUrl = findViewById(R.id.etUrl)
        btnAdBlock = findViewById(R.id.btnAdBlock)
        btnBookmarks = findViewById(R.id.btnBookmarks)
        btnHistory = findViewById(R.id.btnHistory)

        tabLayoutContainer = findViewById(R.id.tabLayoutContainer)
        btnNewTab = findViewById(R.id.btnNewTab)
        progressBar = findViewById(R.id.progressBar)
        webContainer = findViewById(R.id.webContainer)
        quickDashboard = findViewById(R.id.quickDashboard)

        updateAdBlockUi()
    }

    private fun setupTabManager() {
        tabManager = TabManager(
            context = this,
            container = webContainer,
            webViewFactory = { createLightweightWebView() },
            onTabChanged = { activeTab, index ->
                updateNavStateForTab(activeTab)
            },
            onTabListUpdated = { tabs, activeIndex ->
                renderTabs(tabs, activeIndex)
            }
        )
    }

    /**
     * Instantiates and configures a lightweight, zero-tracking WebView.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createLightweightWebView(): WebView {
        val webView = WebView(this)

        // Low-RAM display optimizations
        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true

        // Strict Zero-Tracking Privacy Settings
        settings.saveFormData = false
        @Suppress("DEPRECATION")
        settings.savePassword = false
        settings.databaseEnabled = false
        settings.setGeolocationEnabled(false)

        // Automotive UI scaling & media controls
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = true // Prevent video autoplay eating bandwidth/RAM

        // Block third-party tracking cookies
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString()
                if (adBlocker.isEnabled && adBlocker.isAd(url)) {
                    return adBlocker.createEmptyResponse()
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                // Handle standard web protocols internally
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                // Handle external intents (e.g., tel, geo, mailto) safely
                return try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                    true
                } catch (e: Exception) {
                    true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (view == tabManager.getActiveTab()?.webView) {
                    isLoadingPage = true
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = 10
                    btnReload.setImageResource(R.drawable.ic_close)
                    btnReload.contentDescription = getString(R.string.action_stop)

                    if (!url.isNullOrBlank() && url != "about:blank") {
                        etUrl.setText(url)
                        quickDashboard.visibility = View.GONE
                    }
                }
                // Auto-purge check on page navigation
                historyManager.purgeExpired()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (view == tabManager.getActiveTab()?.webView) {
                    isLoadingPage = false
                    progressBar.visibility = View.GONE
                    btnReload.setImageResource(R.drawable.ic_refresh)
                    btnReload.contentDescription = getString(R.string.action_reload)

                    if (!url.isNullOrBlank() && url != "about:blank") {
                        etUrl.setText(url)
                        val title = view.title ?: url
                        tabManager.updateActiveTabDetails(title, url)

                        // Record to 24-hour zero-tracking history
                        historyManager.addEntry(title, url)

                        // Update Bookmark star icon state
                        updateBookmarkStarState(url)
                    } else {
                        quickDashboard.visibility = View.VISIBLE
                    }

                    updateNavButtons()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (view == tabManager.getActiveTab()?.webView) {
                    if (newProgress < 100) {
                        progressBar.visibility = View.VISIBLE
                        progressBar.progress = newProgress
                    } else {
                        progressBar.visibility = View.GONE
                    }
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                if (view == tabManager.getActiveTab()?.webView && !title.isNullOrBlank()) {
                    tabManager.updateActiveTabDetails(title, view.url)
                }
            }
        }

        return webView
    }

    private fun setupListeners() {
        // Navigation Buttons
        btnBack.setOnClickListener {
            val webView = tabManager.getActiveTab()?.webView
            if (webView != null && webView.canGoBack()) {
                webView.goBack()
            }
        }

        btnForward.setOnClickListener {
            val webView = tabManager.getActiveTab()?.webView
            if (webView != null && webView.canGoForward()) {
                webView.goForward()
            }
        }

        btnReload.setOnClickListener {
            val webView = tabManager.getActiveTab()?.webView ?: return@setOnClickListener
            if (isLoadingPage) {
                webView.stopLoading()
                isLoadingPage = false
                progressBar.visibility = View.GONE
                btnReload.setImageResource(R.drawable.ic_refresh)
            } else {
                webView.reload()
            }
        }

        // Bookmark Star Button
        btnBookmarkStar.setOnClickListener {
            val activeTab = tabManager.getActiveTab() ?: return@setOnClickListener
            val currentUrl = activeTab.webView.url
            if (currentUrl.isNullOrBlank() || currentUrl == "about:blank") return@setOnClickListener

            val title = activeTab.webView.title ?: currentUrl
            val isNowBookmarked = bookmarkManager.toggleBookmark(title, currentUrl)
            updateBookmarkStarState(currentUrl)

            val msg = if (isNowBookmarked) getString(R.string.bookmark_added) else getString(R.string.bookmark_removed)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // URL Input & Search Execution
        etUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                performNavigationOrSearch(etUrl.text.toString().trim())
                hideKeyboard()
                return@setOnEditorActionListener true
            }
            false
        }

        // Host-based Ad-Blocker Toggle
        btnAdBlock.setOnClickListener {
            adBlocker.isEnabled = !adBlocker.isEnabled
            updateAdBlockUi()
            val statusMsg = if (adBlocker.isEnabled) {
                getString(R.string.adblock_enabled)
            } else {
                getString(R.string.adblock_disabled)
            }
            Toast.makeText(this, statusMsg, Toast.LENGTH_SHORT).show()

            // Reload current tab to apply blocking change
            tabManager.getActiveTab()?.webView?.reload()
        }

        // Bookmarks Dialog
        btnBookmarks.setOnClickListener {
            showBookmarksDialog()
        }

        // 24-Hour History & Privacy Dialog
        btnHistory.setOnClickListener {
            showHistoryDialog()
        }

        // New Tab Button (Capped at 5)
        btnNewTab.setOnClickListener {
            if (tabManager.canOpenNewTab) {
                tabManager.openNewTab(DEFAULT_SEARCH_URL)
            } else {
                Toast.makeText(this, getString(R.string.max_tabs_reached), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupQuickDashboard() {
        findViewById<Button>(R.id.btnQuickDuck)?.setOnClickListener {
            loadInActiveTab("https://lite.duckduckgo.com")
        }
        findViewById<Button>(R.id.btnQuickMaps)?.setOnClickListener {
            loadInActiveTab("https://maps.google.com")
        }
        findViewById<Button>(R.id.btnQuickYouTube)?.setOnClickListener {
            loadInActiveTab("https://m.youtube.com")
        }
        findViewById<Button>(R.id.btnQuickWikipedia)?.setOnClickListener {
            loadInActiveTab("https://en.m.wikipedia.org")
        }
    }

    private fun performNavigationOrSearch(input: String) {
        if (input.isBlank()) return

        val targetUrl = when {
            // Valid explicit web scheme
            input.startsWith("http://") || input.startsWith("https://") -> input
            // Likely web domain without scheme (e.g. google.com or maps.google.com)
            Patterns.WEB_URL.matcher(input).matches() || (input.contains(".") && !input.contains(" ")) -> {
                "https://$input"
            }
            // Search query fallback using DuckDuckGo Lite
            else -> {
                val encodedQuery = URLEncoder.encode(input, "UTF-8")
                "https://lite.duckduckgo.com/lite/?q=$encodedQuery"
            }
        }

        loadInActiveTab(targetUrl)
    }

    private fun loadInActiveTab(url: String) {
        val tab = tabManager.getActiveTab()
        if (tab != null) {
            tab.webView.loadUrl(url)
            quickDashboard.visibility = View.GONE
        } else {
            tabManager.openNewTab(url)
        }
    }

    private fun updateNavStateForTab(tab: BrowserTab) {
        val currentUrl = tab.webView.url
        if (!currentUrl.isNullOrBlank() && currentUrl != "about:blank") {
            etUrl.setText(currentUrl)
            quickDashboard.visibility = View.GONE
            updateBookmarkStarState(currentUrl)
        } else {
            etUrl.setText("")
            quickDashboard.visibility = View.VISIBLE
            updateBookmarkStarState(null)
        }
        updateNavButtons()
    }

    private fun updateNavButtons() {
        val webView = tabManager.getActiveTab()?.webView
        val canGoBack = webView?.canGoBack() ?: false
        val canGoForward = webView?.canGoForward() ?: false

        btnBack.isEnabled = canGoBack
        btnBack.alpha = if (canGoBack) 1.0f else 0.4f

        btnForward.isEnabled = canGoForward
        btnForward.alpha = if (canGoForward) 1.0f else 0.4f
    }

    private fun updateBookmarkStarState(url: String?) {
        val isBookmarked = bookmarkManager.isBookmarked(url)
        if (isBookmarked) {
            btnBookmarkStar.setImageResource(R.drawable.ic_star_filled)
            btnBookmarkStar.setColorFilter(ContextCompat.getColor(this, R.color.accent_yellow))
        } else {
            btnBookmarkStar.setImageResource(R.drawable.ic_star)
            btnBookmarkStar.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun updateAdBlockUi() {
        if (adBlocker.isEnabled) {
            btnAdBlock.setColorFilter(ContextCompat.getColor(this, R.color.accent_green))
        } else {
            btnAdBlock.setColorFilter(ContextCompat.getColor(this, R.color.text_muted))
        }
    }

    /**
     * Renders up to 5 tabs dynamically in the top tab strip.
     * High-contrast visual highlighting for the active tab.
     */
    private fun renderTabs(tabs: List<BrowserTab>, activeIndex: Int) {
        tabLayoutContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (i in tabs.indices) {
            val tab = tabs[i]
            val tabView = inflater.inflate(R.layout.item_tab, tabLayoutContainer, false)
            val tvTitle = tabView.findViewById<TextView>(R.id.tvTabTitle)
            val btnClose = tabView.findViewById<ImageButton>(R.id.btnCloseTab)

            // Dynamic title formatting
            val tabTitle = if (tab.title.isNotBlank()) tab.title else "Tab ${tab.id}"
            tvTitle.text = tabTitle

            // High-contrast active tab visual highlight
            if (i == activeIndex) {
                tabView.setBackgroundResource(R.drawable.bg_tab_active)
                tvTitle.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
                btnClose.setColorFilter(ContextCompat.getColor(this, R.color.text_primary))
            } else {
                tabView.setBackgroundResource(R.drawable.bg_tab_inactive)
                tvTitle.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                btnClose.setColorFilter(ContextCompat.getColor(this, R.color.text_muted))
            }

            // Tab switch tap
            tabView.setOnClickListener {
                tabManager.switchToTab(i)
            }

            // Tab close tap
            btnClose.setOnClickListener {
                tabManager.closeTab(i)
            }

            tabLayoutContainer.addView(tabView)
        }

        // Disable or dim new tab button when 5 tabs reached
        val canAdd = tabManager.canOpenNewTab
        btnNewTab.alpha = if (canAdd) 1.0f else 0.35f
    }

    // ==========================================
    // Bookmarks Dialog
    // ==========================================
    private fun showBookmarksDialog() {
        val dialog = Dialog(this, R.style.CustomDialogTheme)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_bookmarks)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val lvBookmarks = dialog.findViewById<ListView>(R.id.lvBookmarks)
        val tvEmpty = dialog.findViewById<TextView>(R.id.tvEmptyBookmarks)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnCloseBookmarks)

        // Shortcut buttons inside dialog
        dialog.findViewById<Button>(R.id.btnShortcutDuck)?.setOnClickListener {
            loadInActiveTab("https://lite.duckduckgo.com")
            dialog.dismiss()
        }
        dialog.findViewById<Button>(R.id.btnShortcutMaps)?.setOnClickListener {
            loadInActiveTab("https://maps.google.com")
            dialog.dismiss()
        }
        dialog.findViewById<Button>(R.id.btnShortcutYouTube)?.setOnClickListener {
            loadInActiveTab("https://m.youtube.com")
            dialog.dismiss()
        }
        dialog.findViewById<Button>(R.id.btnShortcutWiki)?.setOnClickListener {
            loadInActiveTab("https://en.m.wikipedia.org")
            dialog.dismiss()
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        fun reloadList() {
            val list = bookmarkManager.getAllBookmarks()
            if (list.isEmpty()) {
                lvBookmarks.visibility = View.GONE
                tvEmpty.visibility = View.VISIBLE
            } else {
                lvBookmarks.visibility = View.VISIBLE
                tvEmpty.visibility = View.GONE

                lvBookmarks.adapter = object : BaseAdapter() {
                    override fun getCount(): Int = list.size
                    override fun getItem(position: Int): BookmarkItem = list[position]
                    override fun getItemId(position: Int): Long = position.toLong()

                    override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
                        val view = convertView ?: LayoutInflater.from(this@MainActivity)
                            .inflate(R.layout.item_bookmark, parent, false)
                        val item = getItem(pos)

                        val tvTitle = view.findViewById<TextView>(R.id.tvBookmarkTitle)
                        val tvUrl = view.findViewById<TextView>(R.id.tvBookmarkUrl)
                        val btnDelete = view.findViewById<ImageButton>(R.id.btnDeleteBookmark)

                        tvTitle.text = item.title
                        tvUrl.text = item.url

                        view.findViewById<View>(R.id.llBookmarkInfo).setOnClickListener {
                            loadInActiveTab(item.url)
                            dialog.dismiss()
                        }

                        btnDelete.setOnClickListener {
                            bookmarkManager.removeBookmarkById(item.id)
                            updateBookmarkStarState(tabManager.getActiveTab()?.webView?.url)
                            reloadList()
                        }

                        return view
                    }
                }
            }
        }

        reloadList()
        dialog.show()
    }

    // ==========================================
    // 24-Hour Zero-Tracking History Dialog
    // ==========================================
    private fun showHistoryDialog() {
        val dialog = Dialog(this, R.style.CustomDialogTheme)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_history)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val lvHistory = dialog.findViewById<ListView>(R.id.lvHistory)
        val tvEmpty = dialog.findViewById<TextView>(R.id.tvEmptyHistory)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnCloseHistory)
        val btnClearAll = dialog.findViewById<Button>(R.id.btnClearAllData)

        btnClose.setOnClickListener { dialog.dismiss() }

        fun reloadList() {
            val list = historyManager.getRecentHistory()
            if (list.isEmpty()) {
                lvHistory.visibility = View.GONE
                tvEmpty.visibility = View.VISIBLE
            } else {
                lvHistory.visibility = View.VISIBLE
                tvEmpty.visibility = View.GONE

                lvHistory.adapter = object : BaseAdapter() {
                    override fun getCount(): Int = list.size
                    override fun getItem(position: Int): HistoryItem = list[position]
                    override fun getItemId(position: Int): Long = list[position].id

                    override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
                        val view = convertView ?: LayoutInflater.from(this@MainActivity)
                            .inflate(R.layout.item_history, parent, false)
                        val item = getItem(pos)

                        val tvTitle = view.findViewById<TextView>(R.id.tvHistoryTitle)
                        val tvUrl = view.findViewById<TextView>(R.id.tvHistoryUrl)
                        val tvTime = view.findViewById<TextView>(R.id.tvHistoryTime)
                        val btnDelete = view.findViewById<ImageButton>(R.id.btnDeleteHistoryEntry)

                        tvTitle.text = item.title
                        tvUrl.text = item.url
                        tvTime.text = item.getFormattedTime()

                        view.findViewById<View>(R.id.llHistoryInfo).setOnClickListener {
                            loadInActiveTab(item.url)
                            dialog.dismiss()
                        }

                        btnDelete.setOnClickListener {
                            historyManager.deleteEntry(item.id)
                            reloadList()
                        }

                        return view
                    }
                }
            }
        }

        // Manual "Clear All Data Now" Button
        btnClearAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear All Data")
                .setMessage("Are you sure you want to permanently clear all browsing history, cache, cookies, and web storage?")
                .setPositiveButton("Clear Everything") { _, _ ->
                    clearAllBrowserData()
                    reloadList()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        reloadList()
        dialog.show()
    }

    /**
     * Purges history, WebView cache, cookies, and local web storage.
     */
    private fun clearAllBrowserData() {
        // 1. Purge history database
        historyManager.clearAll()

        // 2. Clear cache of all WebViews
        for (tab in tabManager.getTabs()) {
            tab.webView.clearCache(true)
            tab.webView.clearHistory()
        }

        // 3. Clear all Cookies
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()

        // 4. Delete HTML5 WebStorage & databases
        WebStorage.getInstance().deleteAllData()

        Toast.makeText(this, getString(R.string.data_cleared), Toast.LENGTH_LONG).show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etUrl.windowToken, 0)
        etUrl.clearFocus()
    }

    override fun onBackPressed() {
        val activeWebView = tabManager.getActiveTab()?.webView
        if (activeWebView != null && activeWebView.canGoBack()) {
            activeWebView.goBack()
        } else if (tabManager.count > 1) {
            // Close active tab if there are multiple tabs open
            tabManager.closeTab(tabManager.getActiveIndex())
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        tabManager.resumeActive()
    }

    override fun onPause() {
        super.onPause()
        tabManager.pauseAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        purgeHandler.removeCallbacks(purgeRunnable)
        tabManager.destroyAll()
    }

    companion object {
        private const val DEFAULT_SEARCH_URL = "https://lite.duckduckgo.com"
        // 15 Minutes = 900,000 milliseconds
        private const val PURGE_INTERVAL_MS = 15 * 60 * 1000L
    }
}
