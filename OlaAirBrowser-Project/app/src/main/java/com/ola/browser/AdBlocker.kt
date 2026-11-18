package com.ola.browser

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceResponse
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.util.HashSet

/**
 * Built-In Host-Based Ad & Tracker Blocker
 *
 * Loads ad/tracker domains into an in-memory HashSet for O(1) lookup speed.
 * Matches exact hosts and parent domains to block mobile ads, trackers, and telemetry
 * before network requests are dispatched by the WebView.
 */
class AdBlocker(context: Context) {

    private val adHosts: HashSet<String> = HashSet(500)
    var isEnabled: Boolean = true

    init {
        loadAdHosts(context)
    }

    /**
     * Loads domains from res/raw/ad_hosts.txt into memory.
     */
    private fun loadAdHosts(context: Context) {
        try {
            val inputStream = context.resources.openRawResource(R.raw.ad_hosts)
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                val trimmed = line?.trim() ?: continue
                // Skip empty lines and comment lines
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue
                }
                // Store in lower-case for case-insensitive O(1) lookup
                adHosts.add(trimmed.lowercase())
            }
            reader.close()
            inputStream.close()
            Log.d(TAG, "Loaded ${adHosts.size} ad/tracker host domains into memory.")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading ad_hosts.txt", e)
        }
    }

    /**
     * Checks whether a given URL belongs to an ad or tracker host.
     * Matches exact hostname and walks up parent domain hierarchy.
     */
    fun isAd(url: String?): Boolean {
        if (!isEnabled || url.isNullOrEmpty()) {
            return false
        }

        try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase() ?: return false

            // Direct O(1) exact match
            if (adHosts.contains(host)) {
                return true
            }

            // Parent domain check (e.g., "adservice.google.com" or "sub.doubleclick.net")
            var dotIndex = host.indexOf('.')
            while (dotIndex != -1 && dotIndex < host.length - 1) {
                val parentDomain = host.substring(dotIndex + 1)
                if (adHosts.contains(parentDomain)) {
                    return true
                }
                dotIndex = host.indexOf('.', dotIndex + 1)
            }
        } catch (e: Exception) {
            // In case of malformed URLs, do not block
            return false
        }

        return false
    }

    /**
     * Generates an empty WebResourceResponse to cancel the network request cleanly.
     */
    fun createEmptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0))
        )
    }

    companion object {
        private const val TAG = "AdBlocker"
    }
}
