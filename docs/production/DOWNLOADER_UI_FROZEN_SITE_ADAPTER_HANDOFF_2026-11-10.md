# Asterion Downloader — approved UI freeze, site adapter repair work

Date: 2026-11-10
Source of approval: Owner confirmed that the v1.3.48 UI is good and requested a checkpoint; subsequent changes target **individual downloaders per site**, not navigation or presentation.

## Immutable UI baseline

- GitHub checkpoint branch: `checkpoint/downloader-v1.3.48-ui-approved-20261110`
- Full checkpoint commit: `b0c13627a64c093677023b9410684ab23431211d`
- Verified source used in distributed v1.3.48 pilot: `ef731f4e826136ff73c19368ef31df41b9c0ba88`
- Verified pilot package: `com.azrael.pixivdumpsync.pilotfunctional`, versionCode 77, versionName 1.3.48-pilot
- Published APK: https://github.com/Azrazieliz/AsterionDownloader/releases/download/asterion-downloader-functional-v1.3.48-ef731f4e82/Asterion-Downloader-Functional-v1.3.48.apk
- SHA-256: `A01FE83F6CEC09F3BE8B02B00212B8FB918675E09CA20693ADDFCC5D1D1EAD66`

### Preserve exactly
- `AsterionHomeActivity.kt` navigation and module routing
- `AsterionAdultHub.kt` direct per-site cards (no Manage Websites dialog), Porn/Hentai and following-creator presentation
- `AsterionCosplayGallery.kt` direct galleries
- `KurohaMediaPreview.kt` Pixiv-style 85dp horizontal media strips
- `SourceIndexPanel.kt` existing details and service controls
- All established categories, saved identities, queues, IndexedSource data, source-specific Sync/CHECK NEW/BACKFILL and original Pixiv UI
- No visual redesign, no alternate navigation, no new extra site-management interface, and no disabling/hiding failed providers.

## New isolated work branch

`work/downloader-site-adapters-errors-20261110`

Site-by-site fixes shall touch parsers, native extractors, authenticated session handling, retriable errors, download transfer verification, paging/cursors and per-site tests. Do not silently modify the approved UI. A necessary UI change requires explicit owner approval.

## Existing architecture and likely error categories

- `com/asterion/videoflow/VideoSiteProfiles.kt` contains site-specific video page URL recognition for most approved video sites.
- `com/asterion/videoflow/SourceDiagnostics.kt` classifies 403 (authentication/browser verification), 429 (rate limits), 502/503 (server trouble), 404 (not found), unsupported extractor, format mismatch and transfer failure.
- `SiteRunControl.kt` owns manual backfill and saved per-site progress; `ProviderBackfillActions.kt` routes to specialized engines.
- `SourceIndex.kt` owns listing metadata, and `KyoraCreatorPreviews.kt` owns public preview sampling; a visible cover does **not** mean the original download worked.
- `AlohaSources.kt` owns 19 approved Porn websites, 7 Hentai, and 7 Cosplay. Site registrations and thumbnail counts are **not** confirmations of download success.
- `CUMS` previously showed an HTTP 403 on device; verify access/login contract before changing its parser. Other per-host error counts have not been supplied or verified.
- An HTTP 403 must not be bypassed by falsifying success or evading website access controls. Valid authorized login/session or documented provider integration is needed.

## Acceptance criteria for EACH site

1. Ground its canonical entry URL, authorized session/login state, exact page URL recognition and supported formats.
2. Index genuine individual media only. Fetch authentic source-owned previews separately; do not silently mark an unverified page as a downloaded original.
3. On user-requested Backfill, save durable page cursor and exact failure state; Sync only when explicitly enabled.
4. Verify original transfer: proper content type, nonempty original bytes, deduplication, final saved-file receipt and retry behavior.
5. On error, report site + HTTP/extractor cause accurately; 403/429/5xx must remain distinct. Never claim success on a queued request or 200 HTML page.
6. Regression-test provider fixtures and download receipts, then compare UI files against the checkpoint. A repair is not accepted if it changes the approved UI.

Working branch starts from the exact frozen UI checkpoint. No backend/site repairs are claimed by this handoff document.
