# Asterion Downloader — source repair and failure register (16 November 2026)

**Status: INCOMPLETE; do not present the build as a completed fix.**  
Canonical source: Azrazieliz/AsterionDownloader. Repair branch: `repair/downloads-nine-providers-backfill-diagnostics-20261116`.  
Device: Samsung Android 17; installed acceptance app `com.azrael.pixivdumpsync.acceptance`, v1.3.69 (observed before repair).
This is a **non-destructive repair**. Existing app installation, signing identity, databases, downloaded files, artist IDs, site cursors, queue leases, and owner-approved layout must remain unchanged.

## Evidence and limits
- User reported eight newly failed video requests, Archivebate catalog/transfer unusable, Rule34Video thumbnails missing although its original downloads succeed, and delayed Backfill including Pixiv (several minutes after tapping).
- Native Asterion Control read-only snapshot recorded **nine** `videoflow.sqlite` error-state records later in testing. The Android bridge's previous API returned only last eight **coarsely classified** records and explicitly did **not** verify live workers.
- A 30-second read-only monitor was separately verified active on PC, output `D:\AsterionControlRelease\diagnostics\downloader_live_20261116.jsonl`. Sampling is observational; log presence alone is not proof of each successful full media transfer.
- On-device historical crash and low-memory exit reasons exist. Full exception stack traces were **not** extracted. The source admits a 4-GB-available-RAM threshold that can restrict second-worker webpage extraction even when Android does not indicate active system low-memory pressure.
- **No authenticated site bypass** is permitted, and a playable unrelated recommended/advertising clip must never be substituted for the selected original.

## Download failure register (actual Samsung errors; NOT repaired by this list)
| Provider / symptom | Known device error | Source repair target | State and precise acceptance condition |
| --- | --- | --- | --- |
| Jav Guru | HTTP 401/403 denied selected transfer | `videoflow/PublisherOriginalExtractors.kt`; Jav Guru selected player resolution; `TransferService.kt` referer/cookies | **OPEN**. Verify HTTP reason and precise approved player route. Full file, playable duration, correct item, and same-owner consent required. Never claim a 403 bypass. |
| FullPorner | Unsupported/current player extractor | `videoflow/SiteSpecificOriginalSources.kt`, `FullPornerOriginalSource`, `TransferService.kt` | **OPEN**. Resolve one actual selected player and save validated complete original; no generic unrelated fallback. |
| JAVIDEO | Selected iframe exposed no attributable direct media | `SiteSpecificOriginalSources.javideoOriginals`, `javideoSelected`, `selectedIframeMedia`, `selectedExtractorFrame` | **OPEN**. Test clicked-play Streamtape iframe, ensure selected identity and complete video bytes; no advert. |
| Pornhub | Public exit/network region unavailable | `videoflow/NetworkRoute.kt`, `PublisherOriginalExtractors.kt`, `TransferService.kt` | **OPEN / ENVIRONMENT DEPENDENT**. Expose explicit route evidence; an accessible authorized network connection must exist. Do not fabricate one. |
| Roshy TV | Current player extractor unsupported | `PublisherOriginalExtractors.resolve` and provider-specific player adapter | **OPEN**. Selected canonical player -> validated complete file, correct provenance and runtime. |
| 3DHentaiX | Original video identity cannot be verified | `SiteSpecificOriginalSources.threeDHentaiSelected`, `ThreeDHentaiSignedOriginal` | **OPEN**. Prove exactly one selected player URL. Never silently save a different scene. |
| HentaiHaven | Selected article exposed no attributable original | `WebDiscovery.selectedDocument`; `SiteSpecificOriginalSources.selectedStatic` | **OPEN**. Identify real accessible episode media and reject site-verification shell/related media. |
| UncensoredHentai | Current player extractor unsupported | `PublisherOriginalExtractors`; `SiteSpecificOriginalSources`/selected player | **OPEN**. Exact selected episode source and playable complete local file. |
| MP4Hentai | Selected file host yielded no verified media after five steps | `SiteSpecificOriginalSources.mp4hentai`, `Mp4HentaiPublishedHost.resolve` | **OPEN**. Resolve authenticated/allowed final media without treating host landing HTML or another item as success. |
| Archivebate | Catalog 18+ Continue, selected /watch/ player blocked | `ArchivebateWatchActivity.kt`, `ArchivebateSelectedMedia.kt`, `WebDiscovery.kt` | **PARTIAL SOURCE PATCH**. Main-frame navigation now allows only verified same-player `mixdrop.ag` -> `mxdrop.click` exact /e/<id> redirects, and watch-page validation accepts `www.archivebate.com`. A site-required real public Continue action / session remains prerequisite; **not device-verified**. |
| Rule34Video | Originals download but posters absent | `Rule34PosterEvidence.kt`, `VideoSearchPreviews.kt`, `KurohaMediaPreview.kt`, `AsterionAdultHub.kt` | **SOURCE PATCH, NOT DEVICE VERIFIED**. Resolve OG/poster for the exact clicked video page, send page-specific Referer, show real thumbnail where published; record 403, absent-poster, content-type or decode failure rather than a silent blank. Do not replace actual video with unrelated thumbnail. |

## Backfill defects and implemented source changes
1. **Pixiv tap-to-start delay — SOURCE PATCH.** `AsterionAccountPanels.pixivSync` and `MainActivity.startSync` previously enumerated all selected artist database records on the UI-thread click path, potentially blocking dispatch in large libraries. This synchronous preflight was removed; `SyncEngine.runBackfillPass` now validates selections on its worker, records artist-enumeration time, and reports first metadata/original starts.
2. **Pixiv request dropped during live sync — SOURCE PATCH.** `SyncForegroundService.onStartCommand` previously returned immediately for any already-running future, losing manual Backfill taps. A durable bounded FIFO for manual Backfill now queues a request and services it after the current run; Stop discards queued requests but does **not** delete cursors/files. Confirm pause/resume, automatic Live priority, process death, and no duplicate downloads on device.
3. **Provider Backfill started after transfers — SOURCE PATCH.** `ProviderBackfillActions.request` previously used `VideoEngine.launch(scan=true)`, which drained pending video transfers before starting provider scans. Redirected to dedicated `SiteRunControl.start` with saved cursor and request timestamp. `SourceIndexPanel` acknowledges the tap immediately without synchronously reconciling all engine Sync settings.
4. **Parallel-worker bottleneck — OPEN.** `VideoAgentAdmission` limits worker two to native-direct originals below available-RAM threshold; webpage jobs therefore queue behind one extractor on a phone showing ~3.18 GB available. Any change must maintain memory stability; test with two real *distinct* webpage downloads and measure byte progress in both before claiming parallelism.
5. **Pixiv existing Kuroha deduplication — OPEN / CONFIGURATION.** Phone reported zero linked old folders and zero indexed pages. Do not delete legacy data or claim deduplication without verified readable original files.

## Failure diagnostics: implemented source changes
- `com/asterion/bridge/AsterionControlBridgeProvider.java`: previous snapshots exposed only an error category and numeric job ID and treated four video failures as `unspecified_error`. The repair enumerates recent failures with **sanitized provider host, sanitized underlying error text, specific resolver component, stage, stage message, verified bytes, attempts, retry_after, request origin and last update**. It reads original `videoflow.sqlite` and per-ID `videoflow-transfer-stages-v2` receipts, without changing the queue. Signing certificate enforcement and read-only scope are retained.
- `VideoErrorDetail.kt`/`VideoFailurePolicy.kt` retain original underlying throwable causes and useful policy classification, but they cannot reconstruct blocked site HTML. Additional detailed stage failure classifications are valuable only when observed on the actual phone.
- Avoid URLs, query-token credentials, cookies, private files and authentication secrets in diagnostic reports. Sanitization and IPC response-size tests are required.

## No-regression acceptance checklist (NOT passed as of first report)
- [ ] Complete Android Acceptance Kotlin compile and `:app:testAcceptanceUnitTest`.
- [ ] Verify the final repair branch source commit is the one compiled.
- [ ] Preserve owner signing certificate and installed package identity; never use ephemeral CI debug key to replace acceptance APK.
- [ ] Install as an in-place update only after signing verification, with user apps/data intact.
- [ ] On phone: all nine failed URLs tested individually; report full playable saved file or exact blocker, no wrong video.
- [ ] On phone: Archivebate landing, selected player redirect and verified full downloaded MP4.
- [ ] On phone: Rule34Video first page real thumbnails and confirmed preserved original download path.
- [ ] On phone: Pixiv Backfill button-to-worker latency with 1,286 artists and previously selected history preserved; verify duplicate prevention and controls.
- [ ] On phone: provider Backfill click-to-worker latency with existing active transfer; distinct transfer work not blocked.
- [ ] On phone: 2+ independent simultaneous webpage downloads progress/verify without process OOM, stalls or repeated failure loops.
- [ ] On phone: no crash/low-memory regression, and IPC diagnostic summaries remain bounded and sanitized.
- [ ] Books EPUB, Koikatsu-card proportions, Pixiv dedup, cosplay modules, and other previously working sources remain intact.

**Release gate:** A successful source commit or Gradle compilation is NOT a verified user-usable app. Do not mark this repair complete until the device tests above succeed. Preserve all existing user data and previous successful provider behavior.
## 16 November device transfer receipt — exact release state

- **Source built**: `2652300bd05a088bc38d16d640b8456ab804b1c7`; Gradle `:app:verifyBundledYtDlp :app:assembleAcceptance` succeeded after the Acceptance Kotlin/Java compile and JVM unit suite passed on the previous code-only commit.
- **APK**: `app-acceptance.apk`, 54,320,617 bytes, SHA-256 `9e714a689fbe2e37e5cef88169afa3cc17eac733e30ce6c9901b98ea8e52d235`, package `com.azrael.pixivdumpsync.acceptance`, versionCode 99, versionName `1.3.70-acceptance`.
- **Verified signer**: Android V2, CN=Asterion Downloader Acceptance, certificate SHA-256 `8914af1b2713e1d7e93c52abfddf11cbc6bba8d7057906161185a4216dcc0c19`.
- **Verified Asterion Control private Wi-Fi file transfer**: 54,320,617/54,320,617 bytes and matching SHA-256, saved on Samsung as `Downloads/Asterion Control/AsterionDownloader-1.3.70-acceptance.apk`; MediaStore URI `content://media/external/downloads/1000119305`.
- **Installation**: NOT VERIFIED. Asterion Control rejected remote `phone_app_launch` because its owner foreground screen was not active; do not report installed, updated, or user-validated. The Samsung's screen was actively showing other apps, so do not force an installer by overriding local owner gates.
- **Provider fixes**: not complete; nine observed failures listed above remain OPEN, including failures based on remote site restrictions or missing publisher-original source. The new APK is a partial repair/test build, not a passing provider release.
