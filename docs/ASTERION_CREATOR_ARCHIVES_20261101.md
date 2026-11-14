# Asterion Downloader — Creator Archives integration (2026-11-01)

## Canonical continuation
- Repository: `Azrazieliz/AsterionDownloader` (renamed from `Pixiv-downloader`).
- Branch: `feature/asterion-unified-downloads-20261030`.
- Last compiled application code/test commit: `532e24370d1c8c4df766f8a2b80fb016fa643382`.
- Android CI run: https://github.com/Azrazieliz/AsterionDownloader/actions/runs/41297722243 — **success** (debug and preview builds plus unit tests).
- Do not reset Pixiv/Kuroha, Kyora, X, Toonily, Books, Games, VideoFlow, TeraBox state or signing key.

## Implementation
- `CreatorArchives.kt`: bounded parallel discovery for public Pawchive, Kemono and Coomer creator indexes; direct creator URL resolution; per-creator post pagination (50/page); attachment extraction; strict trusted-host/path checks; platform/source errors visible; Android DownloadManager queuing with persisted download IDs, deduplication and retry for failed jobs.
- `CreatorArchiveUi.kt`: search by creator name or known creator archive URL; Patreon/FANBOX/Fantia/Gumroad/OnlyFans/Fansly and other service filters; browse post lists and attachment names; saved creator watchlist; explicit manual refresh; enqueue individual post files or next 40 files from a page; next-page backfill.
- `AsterionHomeActivity.kt`: native Creator Archives source tile; existing Koikatsu search preserved as a tile. No new app or package.
- `CreatorArchivesTest.kt`: host/path validation, safe attachment parsing, deduplication and both supported post-list response shapes.
- Current stored preferences are isolated in `asterion_creator_archives`, `asterion_creator_archive_ui`, and `asterion_creator_downloads`.

## Android installation verification
- Installed preview package `com.azrael.pixivdumpsync.nocturne` updated **in place** from 1.3.20-preview to **1.3.22-preview (versionCode 51)** with `adb install -r`; Android reported **Success**. No uninstall, clearing data or production Kuroha update.
- Re-signed the successful CI preview APK using the existing on-PC debug signing key. Verified signed APK certificate SHA-256: `9bc439712a0a460cc4703ebf254aaaf9bf3cc6e67b0ced6fc12162ed3ca3b9ad` (matches the installed preview's established signing identity).
- Signed APK copied to Android phone: `/sdcard/Download/Asterion-Downloader-v1.3.22-CreatorArchives-PreserveData.apk`.
- App launcher activity was started via ADB. Actual Creator Archives tile interaction, authenticated/source network downloads and downloaded-file integrity are **not yet verified** on this phone.

## Strict limitations
- This implementation searches copies **already public on contributor-run archives**; it does not decrypt, unlock or paywall-bypass the original Patreon, FANBOX, Fantia, Gumroad, OnlyFans or Fansly account.
- Archive indexes may return 403, timeout, incomplete imports or dead file links. Such errors are reported; no claim of universal coverage.
- Creator checking and backfill are **manual**. Saving a creator does **not** currently enable scheduled background imports.
- Large archives require successive pages and multiple user-initiated 40-file batches; success is determined by Android DownloadManager, not by search results.
- Games and books continue to use the existing Games/Books screens and torrent/EPUB tools; this change does not silently alter their semantics.
