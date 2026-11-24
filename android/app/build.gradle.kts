plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Values injected by the GitHub Actions build (see .github/workflows/android.yml).
val relayUrl = System.getenv("RELAY_URL") ?: ""
val screenUrl = System.getenv("SCREEN_URL") ?: ""
// A link to the head unit certificate (e.g. a Google Drive share). It is passed as a build
// SECRET, not a repo variable, because the file it points to contains the private key.
val certUrl = System.getenv("CERT_URL") ?: ""
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val keystorePath = System.getenv("SIGNING_KEYSTORE")

android {
    namespace = "com.subzero.teslawaze"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.subzero.teslawaze"
        minSdk = 30
        targetSdk = 35
        versionCode = runNumber
        versionName = "0.1.$runNumber"
        buildConfigField("String", "RELAY_URL", "\"$relayUrl\"")
        buildConfigField("String", "SCREEN_URL", "\"$screenUrl\"")
        buildConfigField("String", "CERT_URL", "\"$certUrl\"")
    }

    // A stable signing key lets each new APK install as an update over the previous one.
    signingConfigs {
        if (keystorePath != null) {
            create("stable") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = System.getenv("SIGNING_ALIAS")
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("stable")
        }
    }

    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Local hotspot mode: a small on-device HTTP + WebSocket server for the Tesla browser.
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
}
