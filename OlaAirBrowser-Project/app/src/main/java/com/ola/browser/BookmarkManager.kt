package com.ola.browser

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Data model for a bookmark item.
 */
data class BookmarkItem(
    val id: String,
    val title: String,
    val url: String,
    val isDefault: Boolean = false
)

/**
 * Bookmark Manager
 *
 * Persists user bookmarks and pre-populated quick shortcuts locally in SharedPreferences as JSON.
 * Lightweight, zero-dependency, and instantly accessible.
 */
class BookmarkManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Pre-populate with default shortcuts if running for the first time
        if (!prefs.contains(KEY_INITIALIZED)) {
            initDefaultBookmarks()
        }
    }

    private fun initDefaultBookmarks() {
        val defaultList = listOf(
            BookmarkItem(UUID.randomUUID().toString(), "DuckDuckGo Lite", "https://lite.duckduckgo.com", true),
            BookmarkItem(UUID.randomUUID().toString(), "Google Maps", "https://maps.google.com", true),
            BookmarkItem(UUID.randomUUID().toString(), "YouTube", "https://m.youtube.com", true),
            BookmarkItem(UUID.randomUUID().toString(), "Wikipedia", "https://en.m.wikipedia.org", true)
        )
        saveBookmarks(defaultList)
        prefs.edit().putBoolean(KEY_INITIALIZED, true).apply()
    }

    /**
     * Retrieves all saved bookmarks.
     */
    fun getAllBookmarks(): List<BookmarkItem> {
        val jsonString = prefs.getString(KEY_BOOKMARKS_JSON, null) ?: return emptyList()
        val list = ArrayList<BookmarkItem>()

        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optString("id", UUID.randomUUID().toString())
                val title = obj.optString("title", "")
                val url = obj.optString("url", "")
                val isDefault = obj.optBoolean("isDefault", false)
                if (url.isNotEmpty()) {
                    list.add(BookmarkItem(id, title, url, isDefault))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing bookmarks JSON", e)
        }

        return list
    }

    /**
     * Checks if a given URL is already bookmarked.
     */
    fun isBookmarked(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val normalized = normalizeUrl(url)
        return getAllBookmarks().any { normalizeUrl(it.url) == normalized }
    }

    /**
     * Adds a bookmark. Returns true if added, false if already exists or invalid.
     */
    fun addBookmark(title: String, url: String): Boolean {
        if (url.isBlank() || url == "about:blank") return false
        if (isBookmarked(url)) return false

        val currentList = getAllBookmarks().toMutableList()
        val displayTitle = if (title.isBlank()) url else title
        val newItem = BookmarkItem(UUID.randomUUID().toString(), displayTitle, url, false)
        currentList.add(newItem)

        return saveBookmarks(currentList)
    }

    /**
     * Removes a bookmark by matching URL.
     */
    fun removeBookmark(url: String): Boolean {
        if (url.isBlank()) return false
        val normalized = normalizeUrl(url)
        val currentList = getAllBookmarks().toMutableList()
        val removed = currentList.removeAll { normalizeUrl(it.url) == normalized }

        if (removed) {
            saveBookmarks(currentList)
        }
        return removed
    }

    /**
     * Removes a bookmark by ID.
     */
    fun removeBookmarkById(id: String): Boolean {
        val currentList = getAllBookmarks().toMutableList()
        val removed = currentList.removeAll { it.id == id }
        if (removed) {
            saveBookmarks(currentList)
        }
        return removed
    }

    /**
     * Toggles bookmark state for the current URL.
     * Returns true if added, false if removed.
     */
    fun toggleBookmark(title: String, url: String): Boolean {
        return if (isBookmarked(url)) {
            removeBookmark(url)
            false
        } else {
            addBookmark(title, url)
            true
        }
    }

    private fun saveBookmarks(list: List<BookmarkItem>): Boolean {
        try {
            val jsonArray = JSONArray()
            for (item in list) {
                val obj = JSONObject()
                obj.put("id", item.id)
                obj.put("title", item.title)
                obj.put("url", item.url)
                obj.put("isDefault", item.isDefault)
                jsonArray.put(obj)
            }
            prefs.edit().putString(KEY_BOOKMARKS_JSON, jsonArray.toString()).apply()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving bookmarks JSON", e)
            return false
        }
    }

    private fun normalizeUrl(url: String): String {
        return url.trim().removeSuffix("/")
    }

    companion object {
        private const val TAG = "BookmarkManager"
        private const val PREFS_NAME = "ola_browser_bookmarks"
        private const val KEY_BOOKMARKS_JSON = "bookmarks_json"
        private const val KEY_INITIALIZED = "bookmarks_initialized"
    }
}
