# Optimization and size reduction rules for OlaAirBrowser
-repackageclasses 'com.ola.browser.internal'
-allowaccessmodification
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

# Preserve WebView JavaScript Interfaces and WebChromeClient/WebViewClient callbacks
-keepclassmembers class * extends android.webkit.WebViewClient {
    public <methods>;
}
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public <methods>;
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Preserve SQLite helpers and models
-keepclassmembers class * extends android.database.sqlite.SQLiteOpenHelper {
    public <init>(...);
}

# Keep our data classes and managers
-keep class com.ola.browser.** { *; }
