# Asterion Downloader — verified regression recovery checkpoint
Date: 2026-11-20. Owner's canonical working branch: `repair/downloads-nine-providers-backfill-diagnostics-20261116`.

## Immutable runtime release candidate
- Android source commit: `0db6f040e4f931fedcb4885f68d11dce20b884b2`.
- Package: `com.azrael.pixivdumpsync.acceptance`; Gradle version `1.3.76-acceptance`.
- Windows PC: `verifyFrozenVideoProviders`, `testPilotUnitTest`, `testAcceptanceUnitTest` **PASSED** on exact runtime commit. GitHub source preflight for this commit **PASSED**.
- Windows APK assembly **PASSED**, 54,857,845 bytes. SHA-256: `6254a6fca0d1e636c14ed416fb36c167ee6fdb2b79d58d559259cc417a38e072`.
- Retained owner's certificate verified: `8914af1b2713e1d7e93c52abfddf11cbc6bba8d7057906161185a4216dcc0c19`.
- Existing Asterion Control native private Wi-Fi transfer **COMPLETED** and Android MediaStore returned matching size/SHA; verified path: `Downloads/Asterion Control/AsterionDownloader-v1.3.76-OwnerSigned-Fixes-20261120.apk`. The delivery receipt explicitly says `installed=false`. Do not claim installation without PackageInstaller/phone verification. PC temporary APK was removed as prescribed.
- Release candidate is frozen at exact source SHA above. Do not infer APK contents from later documentation-only commits.

## Source changes in this candidate (not blanket Android acceptance)
1. **Pornhub:** Live Windows first-party HLS returned HTTP 200 with actual `#EXTM3U` on the selected `im-h.phncdn.com` CDN and signed path containing comma/equal symbols. The previous validator rejected those legitimate variants. Fixed exact CDN/path admission in `PornhubOriginalSource.kt` and regression-tested; no full Android original receipt yet.
2. **JAV Guru:** Retained known receiving-bytes native JK HLS code and restored its pre-regression publisher network route (commit `aa23492c`). No new speculative rewrite or Android complete-file claim.
3. **JAVideo:** Restored earlier selected-player fallback (commit `75960378`). PC genuine Play test reached `play.javideo.net`, HTTP 200 selected API JSON with opaque `t`, but did **not** resolve a full media file. Remains failing. Do not treat an HTML iframe or unsupported yt-dlp URL as a completed original.
4. **CosplayTele:** Retains direct photos and actual direct videos, and includes publisher-declared archive-only videos when advertised, using existing video-only archive extraction; regression tests added.
5. **Pixiv:** When one artist thumbnail loads and other artwork cards are blank, the profile refresh no longer incorrectly considers all previews complete; healthy URLs are preserved, missing ones retried.
6. **Archivebate:** An empty listing-refresh cannot falsely negate independently indexed exact watch cards; access/auth/player failures remain reportable.
7. **OnScreens:** Thumbnail cards bound to the selected recording's own published poster, not a duplicated generic listing cover. PC public HTML had 42 selected article links and 21 unique inline poster sources; selected recording exposes `og:image`. Video downloading remains unverified.
8. **Search:** Removed repeatedly rejected SpankBang/ThisVid/ManyVids from direct and indexed discovery without touching historical downloads. Added broad web-index discovery of original 3D model files and direct public torrent descriptors; does not constitute exhaustive indexing of the whole internet.
9. **Publisher labels:** Plain names, no “curated”, “recordings” or imported-bookmark prefix on displayed approved providers.
10. **Downloads functional UI:** Gallery now reads actual active, queued and failing database media records instead of claiming “no unfinished jobs” while totals are nonzero. Failed Gallery rows have a durable, **unsaved-terminal-only** Clear action. Single-job Torrent duplicate RESUME removed. No visual redesign; all saved originals and active rows preserved.

## Outstanding defects — not accepted/fixed until phone proof
- JAV Guru and JAVideo Android video-byte receipts, Pornhub Android final saved original, OnScreens recording media, TaiAV thumbnails and selected original, LiveCamRips HTTP 401/403, and Cosplay listing original/thumbnail regressions; Porneec known working and OnlyHentai must remain untouched.
- UncensoredHentai selected NHPlayer AJAX path source exists but has not been proven through Android downloaded full original.
- Torrent engine crashes/quarantine/export, true parallel bytes, Pixiv failed backfills, Gallery stalled transfers and other module clear/dismiss operations require phone validation.
- Storage: Git tracked repository is ~7.9 MB, bundled yt-dlp wheel 3.19 MB, APK 54.86 MB, but owner reports ~10 GB installed Android storage. There is an **unbounded** disk thumbnail cache `cacheDir/kuroha_images` in `RemoteImageLoader.kt` and there are resumable chapter/torrent/video caches. This is a concrete potential contributor but the actual 10 GB breakdown has **not** been measured. Never wipe app data, incomplete media, resumable fragments or saved originals to hide usage.
- UI premium redesign, unrelated 3D local-file/Gumroad buttons and broad model identity/search flows must not be changed speculatively. Owner explicitly requires design suggestions/approval before layout changes and approval before every new provider.

## Regression rules
- Compare failing engines with last **byte-producing** Git checkpoint before editing. Never equate a parser fixture with a successful transfer.
- Keep Porneec and OnlyHentai unchanged; do not re-add retired or rejected sites, paid storefront redirects or unapproved providers.
- Run selected publisher/player probes on Windows first, then test real bytes and final MediaStore receipts on the phone; preserve existing signer and package. No speculative multi-provider rewrites or repeated debug APK builds.
- Android app updates must be explicitly confirmed installed, not merely reported transferred. Use existing Asterion Control bridge. Never replace/re-pair its infrastructure.
