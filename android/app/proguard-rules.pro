# The JavaScript bridge is called by name from the web app.
-keepclassmembers class it.justapps.justchords.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Credential Manager / Sign in with Google.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
