# Asterion Downloader v1.3.0 — Continuation and verification record (2026-10-30)

## Canonical continuation
- Repository: `Azrazieliz/Pixiv-downloader`.
- Working branch: `feature/asterion-unified-downloads-20261030`; PR #17 targets main.
- Keep the original Pixiv, cosplay, video databases, downloads, sessions, filters, scheduling, cloud settings, signing identity, and original folder contents.
- One Android app/launcher: `AsterionHomeActivity` with Add, Queue, Library, Sources, Settings. Older Kuroha/Kyora/VideoFlow classes remain internal implementations; do not produce separate app installations.
- Preserve the production package `com.azrael.pixivdumpsync` and the guarded original-key upgrade workflow. Debug and preview signatures are not original production signatures; do not uninstall the installed original to update it. Preview `.nocturne` may coexist separately.

## Implemented in GitHub source
1. `SourceRouter.kt`: routes Pixiv profiles, X posts and profiles, video URLs, comics/Toonily series, and generic image galleries.
2. `AsterionHomeActivity.kt`: one dashboard, intake, queue, local media library, source watches and settings.
3. `XPhotoQueue.kt`, `XPhotoEngine.kt`, `XPhotoScheduler.kt`: X images only, using documented FxTwitter API v2, with deduplication, persisted profile cursors, full-resolution photo URLs, app-private retries and MediaStore files in `Downloads/AsterionDownloader/X/`. Do not route X to video download.
4. `ChapterScraper.kt`, `ChapterQueue.kt`, `ChapterArchives.kt`, `ChapterRecovery.kt`: series/chapter discovery, numeric chapter ordering including decimals, per-page images, local cached pages, ZIP directories and app-level queue persistence. Completed ZIP saved to `Downloads/AsterionDownloader/Comics/`. Any failed page must leave a visible error, not a false complete archive.
5. `NameDiscovery.kt`, `KeywordSources.kt`: saved creator/title watches, Internet Archive advanced search plus optional configured SearXNG JSON index, optional user-defined search URLs with name substitution. Query words are not silently removed. Actual result URLs are retained. Search success never guarantees global completeness.
6. Video transfer durability: SQLite schema v2 with retry_attempts/retry_after, bounded exponential retry queue, original yt-dlp continuation, staged file/publish verification, optional SOCKS5 transport. Optional local proxy must be running before private-network URL access.
7. Existing Pixiv and image-gallery engines, cloud integrations and app-private persistence remain in the repository.

## User example and requirements
- Test source: `https://toonily.com/serie/moby-dick-17bba228/`.
- Live visible series index on 2026-10-30 lists Chapter 1 through Chapter 109, with fractional chapters (31.5, 39.5, 47.5, 58.5, 75.5, 102.5). Source scraping and CDN image access must be tested on device, not assumed.
- Adult-oriented sources and terms should not be silently excluded from configured search. The engine may support lawful adult media, with user-supplied access, but no claim that unavailable, non-indexed or protected works can be retrieved.
- Deep/alternative-network support depends on an externally active compatible local SOCKS proxy and explicitly configured index/source. No guaranteed crawler across undiscoverable websites exists.
- Avoid pretending that unfinished cloud backup confirmation or universal scraper compatibility is done.

## Verification
- The CI for intermediate commit `d3d4b8d1d6829ebcf0d3d23d48e090b4c8948cc6` passed and produced an installable v1.3.0 preview APK.
- After allowing proxy-routed hidden-service URLs, an older unit test expecting that syntax to be rejected failed. The obsolete assertion was corrected in `WebDiscoveryTest.kt` at commit `4e6f2e4`; do not misreport this as a runtime failure.
- Android emulator API 36 smoke tests were unable to install the APK due to ADB broken pipe, not a demonstrated app exception. Updated smoke workflow at `8ce18acd` switches to API 35 and a single preview install.
- Newer CI APK, on-device download of a Toonily chapter, complete X account archive, actual SOCKS operation and keyword discovery were NOT yet verified as of this record.
- Download result and errors must always be distinguishable, with retries accessible. Do not claim zero errors or universal discoverability.


## Federated source discovery extension (2026-10-30)

- Multiple persisted sources can now be configured in Asterion Downloader > Settings
  using one source definition per line:
  - `searxng|https://search.example` for SearXNG JSON index APIs
  - `html|https://site.example/search?q={query}` for conventional site search result pages
  - `searxng|http://example.onion` with a separately running local Tor-compatible SOCKS proxy
- Source definitions do not blacklist a site for being adult-oriented, unlicensed,
  a mirror, or reachable via Tor. The user chooses configured sources.
- Query text is sent to each provider unmodified apart from URL encoding; source
  metadata, original result URL, relevance score and search history are persisted
  separately from completed download records in `asterion_search_results.sqlite`.
- Discovery does not equate to a playable or legal download. Generic HTML sites
  may change markup or require browser login; password/DRM bypass is not implemented.
  Search indexes never guarantee exhaustive or accurate results.
- Per-source failures are saved to each creator watch and do not make the other
  configured sources fail. A low relevance result remains reviewable rather than
  being automatically downloaded. Duplicate tracking-link variants are collapsed
  for display; the original source URL remains unchanged for downloads.
- If a local SOCKS proxy is enabled, configured search requests use that proxy
  and do not silently fall back to a direct connection. The proxy must be running.
- The existing Pixiv, photo-only X, chapter ZIP, video, signing and data
  preservation contracts remain in force. Confirm live operation and original
  certificate update separately from Android CI.


## Android on-device installation and saved configuration — 2026-10-30

- CI run `41139595378` succeeded and published the direct-download preview:
  `https://github.com/Azrazieliz/Pixiv-downloader/releases/tag/asterion-downloader-v1.3.0-preview-20261030`.
  CI artifact SHA-256:
  `d5c1b5262dd908e5093f607bde466d0449c9e0f22ffb1381c6a93f83c49841bf`.
- Installed on the connected Android 17 phone via ADB:
  `com.azrael.pixivdumpsync.nocturne`, versionCode 29,
  versionName `1.3.0-preview`. Verified launcher activity
  `com.azrael.pixivdumpsync.AsterionHomeActivity` opened successfully.
- Preserved existing production package `com.azrael.pixivdumpsync`,
  versionCode 15, versionName `0.6.1`. No uninstall, data clear or
  production signing replacement.
- For repeatable in-place preview updates the released APK was re-signed
  with the PC's existing Android debug keystore before ADB installation.
  Installed preview signing certificate SHA-256:
  `9bc439712a0a460cc4703ebf254aaaf9bf3cc6e67b0ced6fc12162ed3ca3b9ad`.
  Future preview builds need the SAME key to install over this package without
  removing app-private preferences. The publicly published CI APK was
  signed by a different CI debug key and is not directly upgrade-compatible.
  Do not commit or expose the PC's private signing key.
- Saved persistent `asterion_keyword_sources.xml` SharedPreferences for the
  installed preview (without navigating or editing the user's other apps):
  - `html|https://search.brave.com/search?q={query}&safesearch=off|Brave Search`
  - `html|https://www.pornhub.com/video/search?search={query}|Pornhub`
  - `html|https://www.xvideos.com/?k={query}|XVideos`
  - `html|https://xhamster.com/search/{query}|xHamster`
  - `html|https://www.bing.com/videos/search?q={query}&adlt=off|Bing Videos`
  Verified that the XML exists in the preview app's own sandbox.
  Desktop HTTP reachability was checked; this is NOT proof of working
  Android-side scraping, source availability or successful media downloads.
- Installed official `org.torproject.android` Orbot v17.9.7-RC-1
  (arm64) from Guardian Project GitHub releases. Verified downloaded APK
  SHA-256 `97d3a0a5181394e99fbabdda9fefb73027657cac4c7400c64ebf146a7778860b`.
  The local SOCKS listener appeared on port 9050 but Tor connections did
  NOT pass the external `check.torproject.org/api/ip` check (timeouts).
  Orbot was stopped after testing; `tor_proxy` remains disabled so ordinary
  Asterion search remains usable. Do not claim .onion access works yet.
- Remaining gates: validate actual provider result parsing and downloads
  on device, verify the Toonily chapter ZIP, X photos, post-reboot persistence,
  actual SOCKS/Tor connection, provider error reporting and safe incremental
  upgrades. PR #17 remains separate from production until verified.


## Android preview v1.3.1 on-device UX and Tor verification — 2026-10-30

- The user reported the initial unified home was incomprehensible and that
  Orbot was connected. The v1.3.1 Android home was simplified in
  `AsterionHomeActivity`: clear Download Link and Find Videos by Name
  actions, a Paste from Clipboard control, a persistent results list with
  explicit Download Result buttons, and four main tabs:
  Download, Activity, Files, Settings. Existing engines/data are preserved.
  More technical source management remains accessible from Settings.
- Orbot 127.0.0.1:9050 was tested over ADB forwarding:
  `https://check.torproject.org/api/ip` responded `{"IsTor":true,...}`.
  This supersedes the earlier incomplete Tor bootstrap test.
- Stored `video_options.xml` in the existing preview app sandbox with
  `tor_proxy=true` and `tor_port=9050`. Verified its contents.
  The app's search and video engines were updated to use the Tor proxy
  only for `.onion` URLs; ordinary web sources remain direct.
  A fully functioning `.onion` website extraction was not yet tested.
- GitHub CI and direct APK release v1.3.1 preview succeeded:
  `https://github.com/Azrazieliz/Pixiv-downloader/releases/tag/asterion-downloader-v1.3.1-preview-20261030`.
  Release APK SHA-256:
  `7af341bf4b981c1acabe6a720f1ced40c2650146d9a5a3353ab900938e5af8e6`.
- Installed over the existing preview via ADB with `adb install -r`
  and the same local preview signing keystore, NO app uninstall/data clear.
  Verified Android reports package `com.azrael.pixivdumpsync.nocturne`,
  versionCode 30, versionName `1.3.1-preview`, and successful cold
  launch of `AsterionHomeActivity`.
  Confirmed original production app remained version 0.6.1.
- Verified on-device UI hierarchy contains the simplified labels
  "Download from a link", "Find videos by name",
  "DOWNLOAD LINK", and "PASTE FROM CLIPBOARD".
  Full visual screenshot QA was blocked by a Windows paging-file/memory
  error on the connected PC; no claim of screenshot verification.
- The five configured providers and Tor preferences persisted through
  the incremental preview upgrade. The Android app was not reset.
- Still necessary: site-specific scraping validation, Tor hidden-site
  downloads, comprehensive queue persistence tests, provider search
  relevance, screenshot/UI QA when the PC permits.


## Source-icon launcher and authenticated Add Site — 2026-10-31

- Home is NOT URL-first. The launcher contains four dedicated, vector-icon
  destinations: Pixiv, Toonily (manhwa to chapter ZIP), X (photo artist
  tracking), and Add Site (cosplay, galleries, creator/site binding). Video URL
  and keyword search are secondary. Queue/files and settings remain available.
- Pixiv keeps the existing account session, following feed, artist selection,
  per-artist live sync/backfill, scheduled checks and original Kuroha manager.
  The preview was migrated from the original device database: 1,304 artists,
  5,581 works, 90,576 pages, SQLite integrity=ok. Never clear app-private data.
- Toonily uses the existing ChapterScraper/ChapterArchives production code:
  chapter link discovery, numeric and fractional sorting, lazy image URLs,
  resumable per-page cache, a single ordered ZIP, MediaStore publication only
  after copy verification. A live *metadata/markup-only* probe of the user's
  Toonily Moby Dick reference returned HTTP 200, 115 unique chapter URLs and
  20 img tags in chapter 1; no full end-to-end ZIP was produced by that probe.
- X retains named photo sources, cursors, deduplication, background checks,
  per-artist sync/backfill. X photo mode intentionally excludes videos.
- Add Site retains creator/gallery URL tracking, sync and backfill. A private
  SiteLoginActivity now allows per-domain HTTPS WebView account login.
  It keeps credentials and session cookies in Android WebView storage. The
  existing gallery HttpClient now attaches WebView cookies to the matching
  gallery/page/media requests. Cookie presence alone is not proof that the
  remote server has authenticated or that dynamic pages are supported.
- The original 0.6.1 Android package remains untouched. The connected PC is
  online but Android ADB reports zero devices at the latest check; no claim of
  live installation or authenticated site login test for this newer build.
- Build/release train: v1.3.8 preview; incremental update must use the same
  preview signing certificate as previously installed preview rather than
  the CI's ephemeral key. Never uninstall original or preview to upgrade.
- Still required: real app screenshot QA and Android runtime tests; verify a
  complete Toonily ZIP against live access; verify gallery login on an actual
  supported authenticated website; validate X account backfill, provider
  searches, retry behavior and cloud export. Browser challenges/DRM are not
  bypassed.


### v1.3.8 build and signing proof

- GitHub v1.3.8 preview release was published and tested by Android CI:
  https://github.com/Azrazieliz/Pixiv-downloader/releases/tag/asterion-downloader-v1.3.8-preview-20261030
- CI APK SHA-256: `69431bfedb055de55271205f33c9af066f2e67fe0e65d88a69b9e1a222114cbd`.
  Important: this CI-signed APK is **not** a compatible in-place update for the
  preview installed with the PC's persistent Android debug signing certificate.
- An exact v1.3.8 preview was re-signed on the authorized PC with the pre-existing
  keystore. Verified package `com.azrael.pixivdumpsync.nocturne`, versionCode
  `37`, versionName `1.3.8-preview`, certificate SHA-256
  `9bc439712a0a460cc4703ebf254aaaf9bf3cc6e67b0ced6fc12162ed3ca3b9ad`;
  locally signed APK SHA-256
  `7e32aab84c37d4f676d6e54ec7a1af8e6a9a2332d407c0c3f9ef7171e4b5bb24`.
  Local PC path:
  `%TEMP%\\asterion-install\\Asterion-Downloader-v1.3.8-Preserve-Data.apk`.
- **Not installed:** latest `adb devices -l` returns no connected Android
  devices. Uploading the locally signed artifact to GitHub was blocked by tool
  safety checks, so only the independently installable CI APK is on the release.
  Do not suggest using that APK over the installed preview. Do not uninstall
  the existing original or preview to make it installable.


## CANONICAL KYORA SOURCE — 2026-10-31 (supersedes erroneous placeholder assumption)

- STOP inventing generic substitutes for supposedly missing 4KHD/Buondua
  adapters. The user's original tested Kyora implementation exists in the
  `Azrazieliz/Cosplay-Download` repository, on
  `kyora-v0.6.1-premium-ui`, and is continued on
  `feature/kyora-0.7-flat-terabox-15min-20261023`.
- The real original `AdapterRegistry.kt` contains six adapters:
  `BuonDuaAdapter`, `KiutakuAdapter`, `FourKGirlAdapter`,
  `EveriaAdapter`, `FourKhdAdapter`, `CosplayTeleAdapter`.
  Their implementations are in `CatalogAdapters.kt`,
  `DirectSiteAdapters.kt`, and `KiutakuAdapter.kt`.
- This original engine includes media kinds IMAGE/VIDEO/ARCHIVE, source-specific
  gallery/pagination discovery, archive/resolver paths, and original
  per-provider state. Its `MediaStoreFiles.kt` handles archiving and
  extraction; import it *with its dependencies* and preserve flat output
  semantics rather than dropping media-kind behavior in a generic adapter.
- The `Cosplay-Download/main` branch is an older baseline with placeholders.
  It is NOT canonical for the working Kyora engine. Do not infer active-site
  status from that branch.
- The synthetic `KyoraSiteAdapter.kt` added during this continuation is
  **not canonical and must not be wired**. `AdapterRegistry.kt` was
  restored to the earlier unified branch's original content at commit
  `3eabb1fc95fb3e6f2c69bcab18dbedc5180f85f7`. This rollback
  passed Android CI. Preserve real adapter originals and test migration
  before enabling all six.
- Never delete/clear the current production or preview apps, their databases,
  account cookies, or local NDUS encryption key. Changes to X/TeraBox/Toonily
  are separate and must not be represented as a replacement for Kyora.
