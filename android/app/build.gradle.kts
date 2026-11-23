plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing with the JustApps key, kept outside the repository
// (~/.gradle/gradle.properties or environment variables).
fun secret(vararg names: String): String? = names.firstNotNullOfOrNull { (project.findProperty(it) as String?) ?: System.getenv(it) }
val keystore = secret("JUSTCHORDS_KEYSTORE", "JUSTCLIP_KEYSTORE")

// The web app (npm run build → dist/) is bundled in the APK under assets/web.
val webDist = rootProject.file("../dist")
val copyWeb by tasks.registering(Sync::class) {
    from(webDist) { exclude("sw.js", "sw.mjs", "workbox-*.js") }
    into(layout.buildDirectory.dir("generated/webassets/web"))
    doFirst { require(webDist.resolve("index.html").exists()) { "Run `npm run build` in the repository root first." } }
}

android {
    namespace = "it.justapps.justchords"
    compileSdk = 36

    defaultConfig {
        applicationId = "it.justapps.justchords"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.0"
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/webassets"))

    signingConfigs {
        create("release") {
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = secret("JUSTCHORDS_KEYSTORE_PASSWORD", "JUSTCLIP_KEYSTORE_PASSWORD")
                keyAlias = secret("JUSTCHORDS_KEY_ALIAS", "JUSTCLIP_KEY_ALIAS") ?: "justclip"
                keyPassword = secret("JUSTCHORDS_KEY_PASSWORD", "JUSTCHORDS_KEYSTORE_PASSWORD", "JUSTCLIP_KEY_PASSWORD", "JUSTCLIP_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.named("preBuild") { dependsOn(copyWeb) }

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    testImplementation("junit:junit:4.13.2")
}
