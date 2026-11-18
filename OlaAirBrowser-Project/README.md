# Ola Air Browser ⚡

An ultra-lightweight, zero-tracking Android browser written in Kotlin, crafted specifically for **Android 10 (API 29)** and optimized for the **7-inch 800×480 landscape touchscreen** of the **Ola S1 Air head unit** (3 GB RAM).

APK size is strictly kept **under 2 MB** by using pure native Android XML layouts and Android's built-in WebView without any bulky third-party libraries (no Jetpack Compose, no GeckoView, no heavy Material UI).

---

## 🚀 Key Highlights & Hardware Optimization

1. **800×480 Automotive Cockpit Ergonomics:**
   - **44dp–48dp minimum touch targets** for quick, accurate finger taps while parked.
   - High-contrast automotive dark theme (`#121212` / `#1C1D21`) with vibrant electric cyan (`#00E5FF`) and neon green (`#00E676`) status accents for high visibility in daylight and reduced glare at night.
   - Compact 2-row header (46dp navigation row + 36dp tab row = 82dp total) leaving **~400dp of vertical real estate** for uninterrupted web browsing.

2. **Low-RAM 5-Tab Management (3 GB RAM System):**
   - Strictly capped at 5 concurrent tabs to prevent out-of-memory (OOM) crashes.
   - **Background JS Freezing:** Inactive tabs automatically trigger `webView.onPause()` to freeze JavaScript timers, canvas animations, and audio/video decoders.
   - **Clean Destructors:** Closed tabs unhook listeners, load `about:blank`, purge view hierarchies, and invoke `webView.destroy()`.

3. **Built-in Host-Based Ad & Tracker Blocker:**
   - Pre-loaded with common mobile advertising networks, analytics telemetry, popups, and tracker domains (`res/raw/ad_hosts.txt`).
   - Parsed into an in-memory `HashSet<String>` on startup for **$O(1)$ constant-time lookup**.
   - Subdomain walking in `WebViewClient.shouldInterceptRequest()` immediately intercepts matching ad requests and returns a zero-byte empty response (`WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))`).
   - Instant visual toggle button on the navigation bar with immediate tab reload.

4. **Strict Zero-Tracking Privacy & 24-Hour Auto-Delete History:**
   - **Zero Telemetry:** 0 analytics SDKs, 0 crash reporters, 0 cloud sync, disabled form data saving, disabled password saving, and blocked third-party tracking cookies (`setAcceptThirdPartyCookies(webView, false)`).
   - **24-Hour Expiration:** Visited URLs and timestamps are recorded in an indexed local SQLite database.
   - **Automatic Purging:** All history items older than **86,400,000 ms (24 hours)** are deleted on app launch (`onCreate`), on every page navigation (`onPageStarted`), and every 15 minutes via a periodic background handler.
   - **Manual "Clear All Data Now":** 1-tap complete purge of SQLite history, WebView disk cache, cookies, and HTML5 WebStorage.

5. **Persistent Bookmarks & Quick Shortcuts:**
   - Persisted in local `SharedPreferences` as lightweight JSON.
   - 1-tap bookmark star toggle on the top navigation bar.
   - Pre-populated quick shortcuts: DuckDuckGo Lite, Google Maps, YouTube Mobile, Wikipedia.

---

## 📁 Project Structure

```text
OlaAirBrowser/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/ola/browser/
        │   ├── MainActivity.kt        # Primary activity, UI handling, dialogs & lifecycle
        │   ├── TabManager.kt          # 5-Tab memory-efficient manager & lifecycle
        │   ├── AdBlocker.kt           # In-memory O(1) host-based ad & tracker blocker
        │   ├── BookmarkManager.kt     # SharedPreferences JSON bookmark persistence
        │   └── HistoryManager.kt      # 24-hour auto-expiring SQLite database
        └── res/
            ├── drawable/              # Lightweight vector drawables & shape backgrounds
            ├── layout/
            │   ├── activity_main.xml       # 800x480 landscape layout
            │   ├── item_tab.xml            # Tab strip item
            │   ├── dialog_bookmarks.xml    # Bookmarks modal
            │   ├── item_bookmark.xml       # Bookmark row
            │   ├── dialog_history.xml      # History & privacy modal
            │   └── item_history.xml        # History row
            ├── raw/
            │   └── ad_hosts.txt            # Ad & tracker domain blacklist
            └── values/
                ├── colors.xml         # Dark theme & high-contrast cockpit colors
                ├── dimens.xml         # 44dp+ touch targets & automotive typography
                ├── strings.xml        # Localized strings
                └── styles.xml         # NoActionBar dark automotive theme
```

---

## 🛠️ Building the Release APK (< 2 MB)

Run the Gradle release task from the project directory:

```bash
# On Linux / macOS
./gradlew assembleRelease

# On Windows PowerShell
.\gradlew.bat assembleRelease
```

The optimized, minified APK will be generated at:
`app/build/outputs/apk/release/app-release.apk`

Thanks to `isMinifyEnabled = true` and `isShrinkResources = true`, unused AndroidX classes and resources are stripped, resulting in an APK size well below 2 MB (typically ~900 KB – 1.4 MB).

---

## 📲 Sideloading onto Ola S1 Air

Connect to the scooter's head unit via ADB over Wi-Fi or USB:

```bash
# Connect to head unit IP
adb connect <SCOOTER_IP>:5555

# Sideload the APK
adb install -r app/build/outputs/apk/release/app-release.apk

# Launch the browser directly
adb shell am start -n com.ola.browser/.MainActivity
```
