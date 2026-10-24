# Keep WebView JavaScript interfaces intact (none used, but safe by default).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
