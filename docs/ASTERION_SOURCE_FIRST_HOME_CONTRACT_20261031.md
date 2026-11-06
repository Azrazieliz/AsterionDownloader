# Asterion Downloader — Source-First Home Contract (2026-10-31)

## Canonical navigation — DO NOT regress to URL-first

**Home shows exactly four prominent, individually tappable source tiles** with distinct native Android vector icons:

1. **Pixiv** — artist roster and account login, following feed, automatic new-work sync, complete historical backfill, per-artist selection and progress. Preserve the existing Kuroha/Pixiv engine and database.
2. **Toonily** — manhwa/series address intake, discovery of all accessible chapters and pages, numeric/fractional natural ordering, a **single series ZIP**, progress, error and retry controls. Keep it as a dedicated feature rather than sharing the account binding screen. Protected/challenged sources must report a blocker instead of claiming a complete ZIP.
3. **X** — followed X creator/profile roster, photo-only retrieval, incremental sync and from-first-page backfill, persisted cursors and per-account enable/pause. No automatic video downloads in X account archive.
4. **Add Site** — user-entered cosplay/gallery site or creator URL, source adapter resolution, persistent source/creator binding, automatic incremental sync, historical backfill, per-source enable/selection, and existing advanced gallery/tag manager.

The first screen must **not** be dominated by a URL field, video search, or a generic downloader. Video URL downloading and keyword discovery remain available only as a **secondary tool**, beneath the four icons. The bottom bar exposes **Sources / Downloads / Files**. Settings opens from the header. Android Back from a source panel returns to Sources.

## Data retention and installation

- Preserve all original Kuroha/Pixiv archival records, IDs, sessions, saved cookies (if present), following preferences and cursors. Do not clear app data, uninstall either package, or overwrite the original release signature.
- Verified preview migration from original Pixiv: **1,304 artists**, **5,581 works**, **90,576 page records**, SQLite integrity check `ok`. These are records, not necessarily fully downloaded original images.
- Production package `com.azrael.pixivdumpsync` v0.6.1 is separate. Co-installable preview is `com.azrael.pixivdumpsync.nocturne`; preview updates must reuse the signing certificate already installed.
- Preserve the current five user-configured video search providers and Tor preference in the preview.
- This contract does not assert that live external endpoints always work. Report Android compilation, on-device launch, authenticated access, source-specific scraping and complete archive verification independently.

## Implementation

- Home UI: `app/src/main/java/com/azrael/pixivdumpsync/AsterionHomeActivity.kt`
- Original archive panels: `AsterionAccountPanels.kt`
- Image icons: `app/src/main/res/drawable/ic_source_{pixiv,toonily,x,site}.xml`
- Manhwa: `ChapterScraper.kt`, `ChapterQueue.kt`, `ChapterArchives.kt`
- X: `XPhotoQueue.kt`, `XPhotoEngine.kt`, `XPhotoScheduler.kt`
- Gallery: `com.azrael.galleryflow.AdapterRegistry` and existing gallery flow.
- Video URLs/search remain under `renderAdd()`, not the main screen.

All further UI changes must keep source-specific entry points and both sync/backfill.

## v1.3.7 implementation and verification, 2026-10-31

- Implemented the native 2×2 source-icon dashboard in `AsterionHomeActivity` with dedicated Pixiv, Toonily, X and Add Site panels and secondary Video URL/Search tools.
- CI release `asterion-downloader-v1.3.7-preview-20261030` completed successfully, including preview compilation and its unit-test task. Android CI run `41144791097` passed.
- Android emulator launch/screenshot run `41144791108` was still in progress when this status was recorded; no full visual/device QA claimed.
- Release artifact: `https://github.com/Azrazieliz/Pixiv-downloader/releases/download/asterion-downloader-v1.3.7-preview-20261030/Asterion-Downloader-v1.3.7-Preview.apk`; SHA-256 `92a281a6613782d612e72bc30fde40c7c2660533591e1334edf98fcc1c2814ff`. **The public CI APK has a different debug signing key than the APK previously installed via PC and is not an in-place update for the existing preview.**
- The signed update file was verified locally on the connected PC with installed-preview certificate SHA-256 `9bc439712a0a460cc4703ebf254aaaf9bf3cc6e67b0ced6fc12162ed3ca3b9ad` and SHA-256 `62693be30e71089944a82ce51f697e0b147ab5704e13db09ebaa7564def1efeb`. Not publicly published and not installed; ADB shows no connected Android device. Do not direct an uninstall to work around the signing mismatch, because the preview contains migrated Pixiv data.
- Source-first UI is coded and CI verified, **NOT yet verified installed on the phone**. Source-site authentication and live Toonily Cloudflare-blocked scraping are separate functional issues.
