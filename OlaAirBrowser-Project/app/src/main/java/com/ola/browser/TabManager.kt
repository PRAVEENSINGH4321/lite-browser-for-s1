package com.ola.browser

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import java.util.concurrent.atomic.AtomicInteger

/**
 * Representation of a single Browser Tab.
 */
class BrowserTab(
    val id: Int,
    var title: String,
    var url: String,
    val webView: WebView
)

/**
 * 5-Tab Management (Low-RAM Optimized)
 *
 * Designed for 3 GB RAM head units. Strictly limits open tabs to 5.
 * Pauses background WebViews to freeze JavaScript timers and avoid OOM.
 * Thoroughly destroys and dereferences closed WebViews.
 */
class TabManager(
    private val context: Context,
    private val container: ViewGroup,
    private val webViewFactory: () -> WebView,
    private val onTabChanged: (activeTab: BrowserTab, activeIndex: Int) -> Unit,
    private val onTabListUpdated: (tabs: List<BrowserTab>, activeIndex: Int) -> Unit
) {

    private val tabs: ArrayList<BrowserTab> = ArrayList(MAX_TABS)
    private var activeIndex: Int = -1
    private val tabIdGenerator = AtomicInteger(1)

    val count: Int
        get() = tabs.size

    val canOpenNewTab: Boolean
        get() = tabs.size < MAX_TABS

    fun getTabs(): List<BrowserTab> = tabs

    fun getActiveTab(): BrowserTab? {
        return if (activeIndex in 0 until tabs.size) tabs[activeIndex] else null
    }

    fun getActiveIndex(): Int = activeIndex

    /**
     * Initializes the first tab with an optional starting URL.
     */
    fun initFirstTab(initialUrl: String): BrowserTab {
        return openNewTab(initialUrl) ?: tabs[0]
    }

    /**
     * Opens a new tab capped strictly at MAX_TABS (5).
     * Returns the created BrowserTab, or null if maximum tabs reached.
     */
    fun openNewTab(url: String?): BrowserTab? {
        if (!canOpenNewTab) {
            Log.w(TAG, "Cannot open tab: Maximum of $MAX_TABS tabs reached.")
            return null
        }

        val tabId = tabIdGenerator.getAndIncrement()
        val webView = webViewFactory()
        val defaultTitle = "Tab $tabId"
        val startUrl = url ?: ""

        val newTab = BrowserTab(tabId, defaultTitle, startUrl, webView)
        tabs.add(newTab)

        // Switch to the newly created tab
        switchToTab(tabs.size - 1)
        onTabListUpdated(tabs, activeIndex)

        if (startUrl.isNotEmpty()) {
            webView.loadUrl(startUrl)
        }

        return newTab
    }

    /**
     * Switches the active tab to the specified index.
     * Detaches previous WebView and calls onPause() to conserve RAM and CPU.
     * Attaches target WebView and calls onResume().
     */
    fun switchToTab(index: Int): BrowserTab? {
        if (index !in 0 until tabs.size) return null

        val previousTab = getActiveTab()
        if (previousTab != null && previousTab != tabs[index]) {
            // Pause JavaScript execution and animations to free CPU/RAM
            previousTab.webView.onPause()
            container.removeView(previousTab.webView)
        }

        activeIndex = index
        val currentTab = tabs[index]

        // Ensure active WebView is attached to container
        if (currentTab.webView.parent == null) {
            container.addView(
                currentTab.webView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        currentTab.webView.onResume()

        onTabChanged(currentTab, activeIndex)
        onTabListUpdated(tabs, activeIndex)

        return currentTab
    }

    /**
     * Closes a tab at the given index.
     * Safely cleans up and destroys the WebView instance.
     */
    fun closeTab(index: Int): BrowserTab? {
        if (index !in 0 until tabs.size) return null

        val closingTab = tabs.removeAt(index)

        // Thorough WebView cleanup to prevent native/Java memory leaks
        try {
            closingTab.webView.stopLoading()
            closingTab.webView.loadUrl("about:blank")
            closingTab.webView.onPause()
            closingTab.webView.clearHistory()
            closingTab.webView.removeAllViews()
            container.removeView(closingTab.webView)
            closingTab.webView.destroy()
            Log.d(TAG, "Successfully destroyed tab #${closingTab.id}")
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying WebView for tab #${closingTab.id}", e)
        }

        // Adjust active index
        when {
            tabs.isEmpty() -> {
                activeIndex = -1
                // Open a fresh tab so user is never left with an empty shell
                return openNewTab(DEFAULT_HOME_URL)
            }
            activeIndex >= tabs.size -> {
                activeIndex = tabs.size - 1
            }
            activeIndex == index -> {
                // Keep activeIndex the same (now pointing to next item)
            }
            activeIndex > index -> {
                activeIndex--
            }
        }

        return switchToTab(activeIndex)
    }

    /**
     * Updates title and URL of the active tab.
     */
    fun updateActiveTabDetails(title: String?, url: String?) {
        val current = getActiveTab() ?: return
        if (!title.isNullOrBlank()) {
            current.title = title
        }
        if (!url.isNullOrBlank()) {
            current.url = url
        }
        onTabListUpdated(tabs, activeIndex)
    }

    /**
     * Pauses all tabs (e.g. on activity onPause or onStop).
     */
    fun pauseAll() {
        for (tab in tabs) {
            try {
                tab.webView.onPause()
            } catch (ignored: Exception) {}
        }
    }

    /**
     * Resumes the active tab (e.g. on activity onResume).
     */
    fun resumeActive() {
        getActiveTab()?.webView?.onResume()
    }

    /**
     * Destroys all open tabs during Activity destruction.
     */
    fun destroyAll() {
        for (tab in tabs) {
            try {
                tab.webView.stopLoading()
                tab.webView.loadUrl("about:blank")
                tab.webView.onPause()
                tab.webView.removeAllViews()
                container.removeView(tab.webView)
                tab.webView.destroy()
            } catch (ignored: Exception) {}
        }
        tabs.clear()
        activeIndex = -1
    }

    companion object {
        private const val TAG = "TabManager"
        const val MAX_TABS = 5
        const val DEFAULT_HOME_URL = "https://lite.duckduckgo.com"
    }
}
