# Credential Manager / Sign in with Google.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }

# NewPipeExtractor (audio of YouTube links): it runs YouTube's player JavaScript with Rhino.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn org.mozilla.javascript.engine.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**
-dontwarn javax.annotation.**
