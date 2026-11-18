package com.ola.browser

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data model for a history record.
 */
data class HistoryItem(
    val id: Long,
    val title: String,
    val url: String,
    val timestamp: Long
) {
    /**
     * Human-readable relative timestamp for display in the automotive cockpit UI.
     */
    fun getFormattedTime(): String {
        val diffMs = System.currentTimeMillis() - timestamp
        val minutes = diffMs / (60 * 1000)
        val hours = diffMs / (60 * 60 * 1000)

        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            else -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
    }
}

/**
 * Strict Zero-Tracking Privacy & 24-Hour Auto-Delete History Manager
 *
 * Implemented using native SQLite for atomic operations, indexing, and zero external dependencies.
 * All entries strictly expire and are permanently erased after 86,400,000 milliseconds (24 hours).
 */
class HistoryManager(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_HISTORY (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TITLE TEXT NOT NULL,
                $COL_URL TEXT NOT NULL,
                $COL_TIMESTAMP INTEGER NOT NULL
            )
            """.trimIndent()
        )
        // Create index on timestamp for fast 24-hour expiration purge queries
        db.execSQL("CREATE INDEX idx_history_timestamp ON $TABLE_HISTORY($COL_TIMESTAMP)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_HISTORY")
        onCreate(db)
    }

    /**
     * Records a visited page with the exact Unix timestamp.
     * Automatically triggers a purge of expired entries (> 24 hours).
     */
    @Synchronized
    fun addEntry(title: String, url: String) {
        if (url.isBlank() || url == "about:blank") return

        val displayTitle = if (title.isBlank()) url else title
        val now = System.currentTimeMillis()

        try {
            // First purge any records older than 24 hours
            purgeExpired()

            val db = writableDatabase

            // Deduplicate if the same URL was visited in the last 60 seconds
            val cursor = db.query(
                TABLE_HISTORY,
                arrayOf(COL_ID),
                "$COL_URL = ? AND $COL_TIMESTAMP > ?",
                arrayOf(url, (now - 60_000L).toString()),
                null, null, null
            )

            val exists = cursor.moveToFirst()
            val existingId = if (exists) cursor.getLong(0) else -1L
            cursor.close()

            if (exists && existingId != -1L) {
                // Update timestamp and title of recent visit
                val values = ContentValues().apply {
                    put(COL_TITLE, displayTitle)
                    put(COL_TIMESTAMP, now)
                }
                db.update(TABLE_HISTORY, values, "$COL_ID = ?", arrayOf(existingId.toString()))
            } else {
                // Insert new history entry
                val values = ContentValues().apply {
                    put(COL_TITLE, displayTitle)
                    put(COL_URL, url)
                    put(COL_TIMESTAMP, now)
                }
                db.insert(TABLE_HISTORY, null, values)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error recording history entry", e)
        }
    }

    /**
     * Automatically deletes all history entries older than 24 hours (86,400,000 milliseconds).
     */
    @Synchronized
    fun purgeExpired(): Int {
        val cutoffTimestamp = System.currentTimeMillis() - EXPIRATION_WINDOW_MS
        var deletedRows = 0

        try {
            val db = writableDatabase
            deletedRows = db.delete(
                TABLE_HISTORY,
                "$COL_TIMESTAMP < ?",
                arrayOf(cutoffTimestamp.toString())
            )
            if (deletedRows > 0) {
                Log.d(TAG, "Purged $deletedRows expired history entries older than 24h.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error purging expired history", e)
        }

        return deletedRows
    }

    /**
     * Retrieves all valid history records strictly within the last 24 hours, newest first.
     */
    @Synchronized
    fun getRecentHistory(): List<HistoryItem> {
        purgeExpired()
        val list = ArrayList<HistoryItem>()

        try {
            val db = readableDatabase
            val cursor = db.query(
                TABLE_HISTORY,
                arrayOf(COL_ID, COL_TITLE, COL_URL, COL_TIMESTAMP),
                null, null, null, null,
                "$COL_TIMESTAMP DESC"
            )

            if (cursor.moveToFirst()) {
                do {
                    val id = cursor.getLong(0)
                    val title = cursor.getString(1)
                    val url = cursor.getString(2)
                    val timestamp = cursor.getLong(3)
                    list.add(HistoryItem(id, title, url, timestamp))
                } while (cursor.moveToNext())
            }
            cursor.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching recent history", e)
        }

        return list
    }

    /**
     * Deletes a single history record by ID.
     */
    @Synchronized
    fun deleteEntry(id: Long): Boolean {
        return try {
            val db = writableDatabase
            val rows = db.delete(TABLE_HISTORY, "$COL_ID = ?", arrayOf(id.toString()))
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting history entry: $id", e)
            false
        }
    }

    /**
     * Completely purges the entire history table immediately (manual "Clear All Data" action).
     */
    @Synchronized
    fun clearAll(): Boolean {
        return try {
            val db = writableDatabase
            db.delete(TABLE_HISTORY, null, null)
            Log.d(TAG, "Cleared entire history database.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing history database", e)
            false
        }
    }

    companion object {
        private const val TAG = "HistoryManager"
        private const val DATABASE_NAME = "ola_browser_history.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_HISTORY = "history"
        private const val COL_ID = "id"
        private const val COL_TITLE = "title"
        private const val COL_URL = "url"
        private const val COL_TIMESTAMP = "timestamp"

        // 24 Hours in Milliseconds = 86,400,000 ms
        const val EXPIRATION_WINDOW_MS = 86_400_000L
    }
}
