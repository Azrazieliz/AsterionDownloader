# Asterion Downloader Pilot — pre-Nexus functional test delivery
Date: 2026-11-09

## Decision
Ship a temporary, separate Android app so real feature tests can proceed now, without waiting for old Downloader's missing permanent signing identity or for the future Downloader + Rounder merge. Do not replace, uninstall, reset or claim to migrate the existing app.

## Source and build
- Canonical repository: Azrazieliz/AsterionDownloader.
- Source branch: feature/asterion-unified-downloads-20261030.
- Pilot build type: pilot; applicationIdSuffix .pilot; version 1.3.46-pilot, versionCode 75.
- Android package: com.azrael.pixivdumpsync.pilot.
- Windows Gradle: testPilotUnitTest and assemblePilot PASSED (41 tasks).
- GitHub Actions distribution workflow: .github/workflows/asterion-downloader-pilot.yml.
- GitHub Actions run 42681905896: SUCCEEDED.
- Published release: https://github.com/Azrazieliz/AsterionDownloader/releases/tag/asterion-downloader-pilot-v1.3.46-65dee9b061
- Exact APK: https://github.com/Azrazieliz/AsterionDownloader/releases/download/asterion-downloader-pilot-v1.3.46-65dee9b061/Asterion-Downloader-Pilot-v1.3.46.apk
- Published APK independently downloaded, SHA-256 checked against published .sha256, package and apksigner verified on Windows.
- Published APK SHA-256: 6AC2D7DFA3EB53A98196930719A76449E7D6C85C69BB1E21B99E20094AA5E0D7.
- Published APK signing certificate SHA-256: 3dcd351a653bd5265b27bcd8eec02d4a933c6fef7daf8ecec898a0c958a19d2a.
- File staged at C:\Users\mimim\Downloads\Asterion Control\Asterion-Downloader-Pilot-v1.3.46.apk.
- Existing installed Downloader remains untouched. Distinct Android package deliberately prevents signature replacement conflict.

## Included features
- Preserves existing Downloader engines and source registrations.
- Gallery-first, source-owned actual thumbnail display when indexed.
- Thumbnail-only source probing and independent provider backfill cursors; uncatalogued provider management collapsed.
- Existing per-source sync/backfill, queue, video, Pixiv, X, cosplay and other modules.
- Existing native in-app update source remains for the usual preview/production channels; do not promise compatibility of pilot releases with older production packages.

## Phone acceptance
- The Samsung Asterion Control link was reachable.
- Foreground UI was ChatGPT with an active, nonempty draft; it was not interrupted to run an installation.
- **Pilot APK has NOT been confirmed installed or launched on the phone.**
- **Real source thumbnails and download success have NOT been confirmed on the phone.**
- Native preview pipeline may still return zero thumbnails for providers requiring login or a dedicated adapter; never show fake media.
- The pilot starts with its own private app data, not the older Downloader database. No automatic data migration.
- Continue acceptance directly with the installed pilot: launch, open Porn/Hentai/Pixiv/Cosplay galleries, verify real thumbnails, tap source-owned item to queue actual download, inspect success/failure and per-site Sync/Backfill, then correct observed provider failures.
- Asterion Nexus merge remains the canonical later integration path; preserve the old app and Rounder donor until separately accepted.
