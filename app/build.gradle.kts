import java.security.MessageDigest

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
 
// A single preview signature is required across upgrades. CI's automatically
// generated per-run debug key must never be published as an update APK.
val previewKeyStorePath = System.getenv("ASTERION_PREVIEW_KEYSTORE")
val previewStorePassword = System.getenv("ASTERION_PREVIEW_STORE_PASSWORD")
val previewKeyAlias = System.getenv("ASTERION_PREVIEW_KEY_ALIAS")
val previewKeyPassword = System.getenv("ASTERION_PREVIEW_KEY_PASSWORD")
val hasStablePreviewKey = listOf(
    previewKeyStorePath, previewStorePassword, previewKeyAlias, previewKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.azrael.pixivdumpsync"
    compileSdk = 35
    // Android Gradle Plugin instruments only debug by default.
    // Run transfer simulation against the isolated PhoneTest target instead.
    testBuildType = "phonepilot"

    defaultConfig {
        applicationId = "com.azrael.pixivdumpsync"
        minSdk = 29
        targetSdk = 35
        versionCode = 109
        versionName = "1.3.80"

        testInstrumentationRunner = "com.azrael.pixivdumpsync.DeviceTransferSimulation"
    }

    signingConfigs {
        if (hasStablePreviewKey) {
            create("stablePreviewKey") {
                storeFile = file(requireNotNull(previewKeyStorePath))
                storePassword = requireNotNull(previewStorePassword)
                keyAlias = requireNotNull(previewKeyAlias)
                keyPassword = requireNotNull(previewKeyPassword)
                enableV2Signing = true
            }
        }
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
            if (hasStablePreviewKey)
                signingConfig = signingConfigs.getByName("stablePreviewKey")
            // Local Windows PC debug signing is persistent when no configured
            // preview signer exists. CI must fail closed on missing key.
            matchingFallbacks += listOf("debug")
        }
        // One-time functional acceptance app before the Downloader–Rounder merge.
        // Separate package identity avoids uninstalling or replacing the owner's
        // differently signed Downloader. Never claim this is an in-place update.
        create("pilot") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".pilotagents"
            versionNameSuffix = "-pilot"
            isDebuggable = true
            matchingFallbacks += listOf("debug")
        }
        // One-off, isolated tester: a different package than the already
        // installed Agents and Acceptance apps. No uninstall/data loss.
        // Its debug signer is CI ephemeral; never offer it as an upgrade.
        create("phonepilot") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".testphone1111"
            versionNameSuffix = "-phone-test"
            isDebuggable = true
            matchingFallbacks += listOf("debug")
        }
        // One isolated post-repair test installation. Existing Acceptance
        // and earlier PhoneTest packages are left untouched because CI's
        // debug certificate cannot update their independently signed APKs.
        create("fieldpilot") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".testphone1113"
            versionNameSuffix = "-field-test"
            isDebuggable = true
            matchingFallbacks += listOf("debug")
        }
        // Final provider-repair trial uses a fresh, isolated package because
        // the owner's .testphone1114 is already installed with a DIFFERENT
        // CI-ephemeral signing certificate. Never erase that app or its data.
        // Production acceptance updates must instead use one retained signer.
        create("providerpilot") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".testphone1115"
            versionNameSuffix = "-provider-test"
            isDebuggable = true
            matchingFallbacks += listOf("debug")
        }
        // Install once alongside previous differently-signed pilots; all
        // future acceptance updates use this exact package and the retained
        // Windows signer. GitHub CI never publishes self-signed updates.
        create("acceptance") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".acceptance"
            versionNameSuffix = "-acceptance"
            // Owner-signed distribution is a release update, never a
            // debug APK. Keep installed package ID and retained signer.
            isDebuggable = false
            // Stable Windows-owned signer allows REAL in-place updates.
            // CI's per-run debug key must never be published for this package.
            signingConfig = if (hasStablePreviewKey)
                signingConfigs.getByName("stablePreviewKey") else null
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


/** Fail closed before producing the ONE remaining acceptance APK.
 * Earlier APKs omitted the wheel declared in VideoExtractorVersion, and
 * silently ran the old unsupported extractor despite passing unit tests.
 * The exact wheel is published by PyPI with an independently checked SHA.
 */
val verifyBundledYtDlp by tasks.registering {
    doLast {
        val wheel = file("src/main/assets/yt_dlp-2026.10.26-py3-none-any.whl")
        check(wheel.isFile) {
            "Required yt-dlp 2026.10.26 wheel is missing. Do not build " +
                "another acceptance APK without the actual pinned binary asset."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        wheel.inputStream().buffered().use { stream ->
            val buffer = ByteArray(131_072)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == "a05c5b5d8562f7e8a62f5334348b406af4930d56d3a65f8021d14af0e22339ae") {
            "Bundled yt-dlp wheel has an unexpected checksum: refusing acceptance APK"
        }
    }
}

// Do not affect ordinary source-only tests; protect actual APK packaging.
tasks.matching {
    it.name == "packageAcceptance" || it.name == "mergeAcceptanceAssets"
}.configureEach {
    dependsOn(verifyBundledYtDlp)
}


/**
 * Owner-reported v1.3.72 working video providers are frozen until independently
 * retested on Android. The seven failing providers are NOT frozen. This fast
 * source-identity gate runs on every Acceptance APK package, even when a build
 * is requested without running the broader JVM unit-test suite.
 * Full behavioral fixtures remain in WorkingVideoProviderFreezeTest.
 */
val verifyFrozenVideoProviders by tasks.registering {
    group = "verification"
    description = "Prevent regressions to accepted working video provider engines"
    doLast {
        val expected = linkedMapOf(
            "ArchivebateSelectedMedia.kt" to "7380651fe85ba193c6de3ca891622291a7cca6d8",
        "RoshyPackedOriginalSource.kt" to "a63a72b58f0ed1c3d0c78790e9e9f5b49dab8e83",
        "FullPornerOriginalSource.kt" to "312878511c2b3fbb2be47e571d6cba5de8fbcd67",
            "FullPornerParallelOriginal.kt" to "598c7392829a599093b0bd20b1a46ed63051446b",
            "ThreeDHentaiSignedOriginal.kt" to "9bff43bbdf5d29479e4ac41636b21a2c3b159fa7",
            "Rule34VideoOriginalSource.kt" to "02d00276a554a92c685eef249656f9bcaf3e846f",
            "EpornerOriginalSource.kt" to "c9c4b21720bfbd702d48dea5be6a11dab38bc485",
            // Owner-demonstrated working on November 21. Lock the source
            // itself; only an explicitly approved provider change can
            // revise this established baseline.
            "BubblecumSelectedOriginalSource.kt" to "65af6f3b99abeace2b25c62836c882898a405e26",
            "FreePornVideosOriginalSource.kt" to "184e349e1b97800746578f79aa2bc1c9250fdeaa"
        )
        for ((filename, knownGoodSha) in expected) {
            val source = file("src/main/java/com/asterion/videoflow/$filename")
            check(source.isFile) { "Frozen video engine source missing: $filename" }
            // Normalize only checkout line endings (LF in Git, CRLF on Windows).
            val bytes = source.readText(Charsets.UTF_8)
                .replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
            val md = MessageDigest.getInstance("SHA-1")
            md.update(("blob " + bytes.size + "\u0000").toByteArray(Charsets.US_ASCII))
            md.update(bytes)
            val actual = md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            check(actual == knownGoodSha) {
                "FROZEN WORKING PROVIDER CHANGED: $filename. " +
                    "Revalidate real original/thumbnail delivery before updating its baseline."
            }
        }
    }
}
tasks.matching { it.name == "packageAcceptance" }.configureEach {
    // Shared selected-media and poster adapters (Hentaibros, Rule34Video,
    // Archivebate catalog) are also covered by behavioral JVM fixtures.
    // No future Acceptance APK may bypass those checks.
    dependsOn(verifyFrozenVideoProviders)
    dependsOn("testAcceptanceUnitTest")
}

/** JVM loopback-original tests write several MiB of genuinely received
 * range bytes. Windows' user TEMP (normally on C:) may have insufficient
 * headroom while Android dex/signing builds run concurrently on D:.
 * Keep test scratch in the same build volume. This does NOT change the
 * Android app, any frozen provider, its 64MiB production reserve or tests'
 * length/hash/ETag/original-integrity assertions.
 */
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    val scratch=project.layout.buildDirectory
        .dir("test-scratch/${name}").get().asFile
    systemProperty("java.io.tmpdir",scratch.absolutePath)
    doFirst {
        check(scratch.isDirectory || scratch.mkdirs()) {
            "Cannot create durable JVM acceptance-test scratch directory"
        }
    }
}
