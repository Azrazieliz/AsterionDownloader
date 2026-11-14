# Asterion Downloader — signed v1.3.51 acceptance checkpoint

Date: 2026-11-11
Status: **APK built, published and verified; device/provider acceptance incomplete.**

## Owner-approved nonnegotiables
- Preserve the approved v1.3.48 Pixiv-style Porn/Hentai/Cosplay UI, site cards and controls.
- Preserve existing app and private data; do not uninstall or overwrite differently signed packages.
- Two independent original-file download agents per module, distinct from discovery/indexing workers.
- Neither preview discovery nor queue enqueue counts as a completed original.
- Respect authorization requirements and rate limits; do not invent source media, fake downloaded files or success.
- Integrate Downloader with Rounder in the planned combined app; consider Core as an isolated subsystem only after its startup/recognition reliability is demonstrated.

## Frozen UI checkpoint
- Branch: `checkpoint/downloader-v1.3.48-ui-approved-20261110`
- Compared to current repair branch: the layout/navigation files `AsterionHomeActivity.kt`, `AsterionCosplayGallery.kt`, `KurohaMediaPreview.kt`, `SourceIndexPanel.kt`, `UnifiedDownloadsUi.kt` remain unchanged.
- `AsterionAdultHub.kt` changed narrowly to enqueue the clicked recognized original video instead of launching an unrelated site-wide backfill. No visual redesign.

## Actual implementation
- VideoFlow: two independent Android processes with SQLite atomic claims and lease heartbeats; watchdog for metadata preparation and stalled transfer stages.
- Modules: two original dispatch agents per module (`ModuleOriginalAgents`) with durable claims, delayed retry scheduling, real completion receipts and separate discovery workers.
- EPUB/comics/Koikatsu/MP4Directs: dedicated two-worker paths or series-aware parallelism already in source; MP4Directs preserves volume order within one series.
- `OriginalFileReceipt`: validates file signatures and ZIP end-of-central-directory; rejects HTML/login/XML provider error files and incomplete saved lengths; compares saved byte count against the expected DownloadManager total when available.
- `DirectDownloadRetry`, `DirectDownloadTracker`, `ModuleOriginalAgents`: require matching verified file receipts before recording completion or finalizing dedup state.
- Provider fixes retained from previous passes: official Eporner player and runtime validation; OMG.xxx to OMG.porn approved migration; Jav Guru/JAVIDEO/XVideos URL routing; HentaiBros/Eporner catalog API parsing; WatchPorn full-runtime original path; corrected permanent/retriable error classification.
- Koikatsu previews preserve image aspect ratio. Performer follows require first-party profile evidence before queuing attributed originals.
- Book volume field removed; existing Rounder confirmed-inventory lookups prevent documented duplicate volumes.

## Signed APK — verified artifact
- Windows source build commit: `168efe80b53af3280492cd9328188bb0e141b151`.
- Release commit (tools-only changes after same app source): `7bfc4235fc5e30d389d26fad432c57463a6069e6`.
- Release tag: `asterion-downloader-v1.3.51-acceptance-7bfc4235fc`.
- APK URL: https://github.com/Azrazieliz/AsterionDownloader/releases/download/asterion-downloader-v1.3.51-acceptance-7bfc4235fc/Asterion-Downloader-v1.3.51-Acceptance.apk
- GitHub release URL: https://github.com/Azrazieliz/AsterionDownloader/releases/tag/asterion-downloader-v1.3.51-acceptance-7bfc4235fc
- Package: `com.azrael.pixivdumpsync.acceptance`.
- Version code/name: `80`, `1.3.51-acceptance`.
- Windows-signed APK size: `50393412` bytes.
- SHA256: `17BE3940EF9B645012E090F4BE018A55CBDB33F9B63343430BC71B5852E0DCD6`.
- Signer SHA256: `9bc439712a0a460cc4703ebf254aaaf9bf3cc6e67b0ced6fc12162ed3ca3b9ad`.
- Windows build: `:app:testAcceptanceUnitTest :app:assembleAcceptance` — successful, 41 Gradle tasks.
- Published GitHub asset downloaded back independently on Windows: exact SHA256, signer, package, version and file length matched.
- Stored persistent signing identity: the Windows workstation Android signing key; private key was not uploaded, committed or disclosed.
- `AsterionInAppUpdater` recognizes exact `acceptance` release tags and enforces matching installed package, signer and checksum. Future upgrades require the same Windows signing certificate and strictly increasing versionCode.

## Truthful acceptance limits
- Samsung Asterion Control reported paired=true, reachable=false on 2026-11-11; phone_ui returned HTTP 503. This exact APK is **not installed, launched or live-tested on Samsung**.
- Third-party provider success is not uniform. Some URLs return HTTP 403 or 429, require authorized login, redirect to unrelated sites, or lack supported full-original extractors. Catalog HTTP 200 or thumbnail evidence does **not** prove original downloads.
- Only an Eporner sample original had an independently verified full MP4 transfer on Windows in the preceding site-level tests; this does not certify other Eporner videos or Samsung.
- The build is an **acceptance APK**, not a fully proven production release. Its separate package cannot read older Android app-private settings automatically and does not silently migrate data.
- Core is not fused into this APK. Core's startup and recognition/taxonomy defects must be isolated before merging so they cannot block downloaded-file access.

## Remaining acceptance gates
1. On an authorized/reachable Samsung, install *Asterion Downloader Acceptance* without uninstalling older versions and confirm its label, package, module UI, preview cards and file persistence.
2. Verify two simultaneous originals within and across modules with durable byte counts, unique claims, proper pause/cancel/retry and no staging leaks.
3. For each provider, use a permitted session, demonstrate exact video/post extraction, full-file receipt and actual saved original. Mark blocked 403/429/unsupported sources accurately, without claiming success.
4. Confirm Rounder file inventory and Core integration separately within the planned merger and its shared data model.
