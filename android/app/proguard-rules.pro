# The JavaScript bridge is called by name from the web app.
-keepclassmembers class it.justapps.justchords.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
