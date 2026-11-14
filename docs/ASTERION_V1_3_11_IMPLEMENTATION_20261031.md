# Asterion Downloader v1.3.11 — implementation and verification (2026-10-31)

Canonical working branch: `feature/asterion-unified-downloads-20261030`.
Signed APK: `artifacts/asterion-signed-installers/Asterion-Downloader-v1.3.11-Preserve-Data.apk`.
Package: `com.azrael.pixivdumpsync.nocturne`; version code 40; version name `1.3.11-preview`.
Signing certificate SHA-256: `9bc439712a0a460cc4703ebf254aaaf9bf3cc6e67b0ced6fc12162ed3ca3b9ad`.
APK SHA-256: `4a70ad798b79fbbc3a0c7c1c411b20f9c48c88b14b4c957da17e32183489eb31`.

## Implemented
- Restored original Kyora six-adapter implementations from `Cosplay-Download/feature/kyora-0.7-flat-terabox-15min-20261023`: BuonDua, Kiutaku, FourKGirl, Everia, FourKhd and CosplayTele. Original provider URL resolvers and archive extractors were integrated; generic fallback remains last.
- Handles image, video and archive media, provider-specific fallback, ZIP/RAR extraction, verified downloaded lengths, collision-safe flat output under the unified gallery folder, and durable NDUS queued transfers.
- Source entry supports Auto / Gallery / Albums selection; generic source kind is persisted. Full automatic per-domain scraper strategy learning remains unverified.
- Toonily chapter requests and image requests reuse authenticated WebView cookies where available. Empty, invalid and truncated image responses are rejected. One series ZIP now uses flat chapter/page-qualified filenames.
- Preserves original Pixiv data, separate production package, existing archive, sessions and signing key. Schema v5 restores visibility of previously retired Kyora sources without automatically turning background jobs back on.
- Restored canonical parser tests and replaced obsolete assertions that required a Kiutaku-only installation.

## Verification
- Windows Android Gradle preview assembled successfully.
- Kotlin compilation succeeded, updated Android JVM toolchain 21 used locally with bytecode target 17. Repository keeps original `jvmToolchain(17)` for Android CI.
- Preview unit tests passed.
- `apksigner` confirmed preview signing identity matches the installed preview.
- Android 17 phone upgraded from 1.3.10-preview to 1.3.11-preview with `adb install -r` (no uninstall or clear); original production Kuroha remains 0.6.1.
- `AsterionHomeActivity` launched successfully. The internal Kyora activity is intentionally not ADB-shell exported; UI validation must use in-app navigation.
- Signed installer copied to Android `Download/Asterion-Downloader-v1.3.11-Preserve-Data.apk` and published on the dedicated artifact branch.

## Outstanding verification and limitations
- Complete authenticated Toonily series ZIP against a real protected chapter CDN still needs live end-to-end validation; browser cookie handoff alone does not bypass server challenges.
- Real all-six-site gallery backfill, cloud NDUS delivery, X following imports, and VideoFlow specific sites require additional live source and account tests. CI/test success is not a claim of successful external download.
- Per-site automatic strategy learning, premium UI controls, and some site-specific availability may need further implementation. Do not mark as completed without evidence.
- Do not uninstall, clear app storage, replace the signing certificate, export the NDUS secret, or modify the production Kuroha package.
