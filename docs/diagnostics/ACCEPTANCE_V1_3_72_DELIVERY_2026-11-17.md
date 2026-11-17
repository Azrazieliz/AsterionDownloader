# Asterion Downloader v1.3.72 Acceptance — signed phone handoff

Date: 2026-11-17

## Identity and provenance

- Built from: `5633873daeb908cada11498ee155f5138e47b90e` on `repair/downloads-nine-providers-backfill-diagnostics-20261116`.
- Package: `com.azrael.pixivdumpsync.acceptance`.
- Version: `1.3.72-acceptance`, versionCode `101`.
- Signed using the retained owner-controlled Acceptance certificate (SHA-256 `8914af1b2713e1d7e93c52abfddf11cbc6bba8d7057906161185a4216dcc0c19`); build script validated certificate, APK signature and manifest.
- SHA256: `f5e97b8a1701d810bc84803ffd61690df152a6b37ef814f84e61007ea64bdf9b`.
- APK byte length: `54406825`.
- PC: `C:\Users\mimim\Downloads\Asterion-Downloader-v1.3.72-Acceptance.apk`.
- Phone: `Downloads/Asterion Control/Asterion-Downloader-v1.3.72-Acceptance.apk`.
- Native Control transfer job `145ec79482ad4f5fb41f2b1bd3d2c109` returned `transferred_verified_not_installed`: phone SHA and length verified. Android installation is **not** claimed.

## Acceptance suite

- Full `:app:testAcceptanceUnitTest --offline` passed after clearing only corrupted generated Gradle test-result files; build process proceeded to signed `:app:assembleAcceptance`.
- `:app:assembleAcceptance`: `BUILD SUCCESSFUL` (37 tasks), verified owner signer and manifest.
- These are source/build tests only, not proof of successful selected-video downloads on Android.

## Source repairs included

- Archivebate strict real catalog/watch-card parsing, public Continue flow and main-process selected Mixdrop resolution; selected CDN was independently verified HTTP 206 video/mp4 with 2,073,790,591 declared bytes (full file not downloaded in this check).
- Jav Guru and Pornhub selected signed HLS source handling, extractor prewarm and yt-dlp fragment byte accounting.
- JAVIDEO first-party selected Play iframe, guarded bounded retries, correct WebView media snapshot.
- HelloPorn VOE, UncensoredHentai NHPlayer and other approved embedded players: authenticated selected iframe navigation and host-scoped cookie handling.
- MP4Hentai selected Mirror/UpFiles public free-form and authorized host-cookie handling; interactive host verification remains dependent on actual owner authorization.
- Primary and secondary video-agent lifecycle/lease scheduling, manual queue independence and memory-aware second-process admission.
- Regression protection for previously working FullPorner, 3DHentaiX, HentaiBros and Rule34Video thumbnail paths.

## Required phone validation

**Do not mark any of these newly fixed:** Archivebate listing/preview/selected complete MP4; Jav Guru and Pornhub full selected HLS; JAVIDEO, HelloPorn, HentaiHaven, UncensoredHentai, MP4Hentai exact original; two independent simultaneous video downloads (with byte progress, final playback and real completion).

Use the app's own Connect Site flow when a publisher/host requires browser verification or account authorization. The app must not claim that CAPTCHA/paywall/account gates were bypassed or misclassify restricted HTML as a media file.

Verify existing settings, queue and downloads survive installation. No uninstall; in-place Acceptance update only.
