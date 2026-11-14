# Asterion Downloader — backend repair v1.3.49

Date: 2026-11-10

## Approved UI checkpoint
- Immutable baseline branch: checkpoint/downloader-v1.3.48-ui-approved-20261110
- Baseline commit: b0c13627a64c093677023b9410684ab23431211d
- Site/backend branch: work/downloader-site-adapters-errors-20261110
- GitHub Actions release build explicitly checks that the six approved UI source files have no differences from the frozen checkpoint.
- User prohibited an additional Manage Websites dialog and repeated UI redesigns. Neither was reintroduced.

## Source-level repairs included in APK
- TransferService.kt: stage-bounded yt-dlp metadata checks, capped preflight budget, byte/progress watchdog on transfers, interrupt/cancel handling, throttled progress database writes, saved per-file stage diagnostics.
- VideoMetadataOutput.kt: parses balanced multiline JSON rather than assuming each console line is one complete object.
- VideoExtractorErrors.kt: extracts safe HTTP/auth/extractor failure classifications without recording signed media URLs or cookies.
- VideoFailurePolicy.kt and VideoDb.kt: no automatic retry for actionable 401/403/404/unsupported cases; metadata parsing failure caps at two automatic retries; temporary 429/5xx/network faults use bounded backoff.
- Scheduling.kt: separately scheduled wake-up at earliest persisted retry due-time instead of waiting up to the periodic 15-minute poll.
- VideoSiteProfiles.kt / WebDiscovery.kt: keeps canonical www/m/fr/en redirects within one approved provider, validates redirects before reusing cookies, identifies browser-verification pages, and uses an already user-connected browser session when direct HTTP is unavailable.
- WebDiscovery.kt uses authentic source image candidates rather than inventing thumbnails; current UI remains frozen.
- Unit tests: VideoTransferReliabilityTest and VideoProviderRoutingTest for metadata, timeout policy, safe error classification, legitimate redirects and site-video-page recognition.

## Verified build
- Release source commit: fca947ea90e87976236ed18954b58510172813c5
- CI workflow: .github/workflows/asterion-downloader-sitefix.yml
- CI run: https://github.com/Azrazieliz/AsterionDownloader/actions/runs/42772896273 — success
- Windows Gradle tests and pilot APK assembly: BUILD SUCCESSFUL
- Android app ID: com.azrael.pixivdumpsync.pilotbackend
- versionCode: 78; versionName: 1.3.49-pilot
- Exact APK URL: https://github.com/Azrazieliz/AsterionDownloader/releases/download/asterion-downloader-backend-v1.3.49-fca947ea90/Asterion-Downloader-Backend-Fix-v1.3.49.apk
- APK SHA-256: 69A37A4B9EB8B0F633554CB18A8F9FF2D2EFC4A41DF8F86B55A8B40A6E129B56
- APK size: 50,331,956 bytes
- Published signer SHA-256: 73bd839633c6e698f5b135bc63c415d560643e5edc3f38886be0d5d68bf26f9f
- Published APK downloaded independently, SHA-256 verified against release asset, package identity and apksigner check passed on Windows.
- Staged copy: C:\Users\mimim\Downloads\Asterion Control\Asterion-Downloader-Backend-Fix-v1.3.49.apk

## Explicit limitations and pending acceptance
- Source-level test success is NOT live proof of any specific provider's original-file transfer. The recording reported numerous 403 and parser errors across third-party sources. Authorized site logins remain necessary where servers restrict access.
- VideoFlow still runs one native yt-dlp download at a time, because embedded Python and process-global native working directory were not validated as safe for parallel execution. Bounded stage timeouts reduce indefinite blocking but do not guarantee fast throughput for all files.
- Some sources require new dedicated adapters, and none of the 19 approved Porn providers is marked end-to-end accepted solely by this release.
- Android Asterion Control phone_ui returned HTTP 503 in this turn, preventing on-device installation, foreground UI inspection, actual download completion and captured per-site logs.
- The distinct .pilotbackend application ID protects older differently signed installs but does NOT migrate the older app's private queues, account sessions or databases. Do not claim seamless in-place update.
- The temporary CI debug signing key is not a permanent update identity. Do not promise in-app updates to a different signed package.
- No original user data or UI checkpoint was modified.

## Next acceptance
Install this verified APK, test a publicly accessible original and authorized 403 provider independently, check actual saved video URI and bytes, observe retry recovery and queue turnover, then record each site's remaining exact error. Fix site-specific adapters using actual page structure and authorized sessions, not guessed workarounds. Preserve the frozen UI and original v1.3.48 baseline.
