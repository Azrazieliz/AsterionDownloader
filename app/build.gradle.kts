plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Original v0.6.1 signing key: external/secret only, never committed to Git.
// Only the dedicated signed-update workflow sets these environment variables.
val originalKeyStorePath = System.getenv("KUROHA_ORIGINAL_KEYSTORE")
val originalStorePassword = System.getenv("KUROHA_STORE_PASSWORD")
val originalKeyAlias = System.getenv("KUROHA_KEY_ALIAS")
val originalKeyPassword = System.getenv("KUROHA_KEY_PASSWORD")
val hasOriginalSigningKey = listOf(
    originalKeyStorePath, originalStorePassword, originalKeyAlias, originalKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.azrael.pixivdumpsync"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.azrael.pixivdumpsync"
        minSdk = 29
        targetSdk = 35
        versionCode = 63
        versionName = "1.3.34"

        testInstrumentationRunner = "android.app.Instrumentation"
    }

    signingConfigs {
        if (hasOriginalSigningKey) {
            create("originalUpgradeKey") {
                storeFile = file(requireNotNull(originalKeyStorePath))
                storePassword = requireNotNull(originalStorePassword)
                keyAlias = requireNotNull(originalKeyAlias)
                keyPassword = requireNotNull(originalKeyPassword)
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        create("preview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".nocturne"
            versionNameSuffix = "-preview"
            isDebuggable = true
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = false
            if (hasOriginalSigningKey) {
                signingConfig = signingConfigs.getByName("originalUpgradeKey")
            }
        }
    }

    packaging { jniLibs { useLegacyPackaging = true } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-39")
    runtimeOnly("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-39")
    runtimeOnly("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-39")
    runtimeOnly("org.libtorrent4j:libtorrent4j-android-x86_64:2.1.0-39")
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    implementation("com.github.junrar:junrar:8.1.1")
    implementation("dev.ffmpegkit-maintained:yt-dlp-android:2.0.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
