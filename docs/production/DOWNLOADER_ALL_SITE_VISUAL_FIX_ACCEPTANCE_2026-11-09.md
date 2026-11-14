# Asterion Downloader — all-site visual gallery repair (2026-11-09)

## Owner-requested corrections
- Exact prior Pixiv-style avatar + horizontal media thumbnail card structure across Porn, Hentai, Cosplay and followed performers. Do not redesign original Pixiv implementation.
- REMOVE the unrequested **MANAGE WEBSITES** button and separate website-control modal from the Porn/Hentai and Cosplay home screens. Never reintroduce it.
- Present ALL approved source entries directly in the relevant gallery, not only the providers that already have indexed artwork; each has its own Sync, CHECK NEW and BACKFILL.
- Keep real thumbnail ownership isolated per site. No invented covers, no site logos falsely presented as video/image previews, no auto download from a catalog preview.
- A failed source shows a factual status and error (including HTTP 403), not a fabricated success.

## Actual fixes in code
- `AsterionAdultHub.kt`: removed management dialog, renders active and not-yet-previewed providers (19 approved Adult sites), full-scope per-source metadata discovery, real catalog cover fallback, source-specific CHECK NEW.
- `AsterionCosplayGallery.kt`: removed management modal, no longer drops sites with no covers, source-specific CHECK NEW and factual provider status.
- `SourceIndex.kt`: previously rotated only four providers on each refresh and paused by a 15-minute UI throttle. Now processes all approved sites in parallel at a bounded maximum of four; preview checks persist last-check/results/errors without advancing history cursors or enqueueing originals; per-site scan entrypoint added; database writes serialized.
- `KyoraCreatorPreviews.kt`: concurrency raised to five for network preview reads, accepts more real media selectors and 24 covers per source instead of six, follows accepted locale/mobile/WWW variations within the provider's own domain, reuses authorized session cookies and public catalog metadata.
- `AllSiteGalleryCoverageTest.kt`: checks the 19-site catalog and genuine preview extraction.
- Native provider backfill and the queue remain the existing engines, not replaced by a mock.

## Built release
- Repository: Azrazieliz/AsterionDownloader
- Branch: feature/asterion-unified-downloads-20261030
- Release source commit: ef731f4e826136ff73c19368ef31df41b9c0ba88
- CI run: https://github.com/Azrazieliz/AsterionDownloader/actions/runs/42716414907 — success
- Windows Gradle: testPilotUnitTest assemblePilot — BUILD SUCCESSFUL, 41 tasks
- Android app package: com.azrael.pixivdumpsync.pilotfunctional, versionCode 77, versionName 1.3.48-pilot
- APK: https://github.com/Azrazieliz/AsterionDownloader/releases/download/asterion-downloader-functional-v1.3.48-ef731f4e82/Asterion-Downloader-Functional-v1.3.48.apk
- Published APK SHA-256: A01FE83F6CEC09F3BE8B02B00212B8FB918675E09CA20693ADDFCC5D1D1EAD66
- Published signing cert SHA-256: 6f6b149853a6539e1bf0c542b3aaface52323b65a457a84f6b93c832393c4ca7
- Independently verified Windows copy: C:\Users\mimim\Downloads\Asterion Control\Asterion-Downloader-Functional-v1.3.48.apk

## Truthful acceptance limits
- Full 19-site SCAN COVERAGE is implemented; real media THUMBNAIL SUCCESS at each provider is NOT confirmed. Some providers require sign-in or return HTTP 403; no automatic bypass.
- Functional download success, queue responsiveness, visual Pixel/Pixiv fidelity and foreground launch on the Samsung have NOT yet been verified with this exact final APK.
- Current Asterion Control phone link was reachable, but the phone was being used in Aloha Browser and no installation/navigation was performed.
- The package is intentionally distinct from prior differently signed Pilots. It does NOT overwrite the old app or migrate its private data.
- Only count a site as indexed when real items have been observed. Do not confuse catalog registration (19) with indexed video results.
- The temporary pilot's per-run CI debug signing certificate is NOT a permanent signing identity; do not advertise seamless updates over older differently signed APKs.
- Next acceptance: install the verified APK, inspect the direct gallery cards, record the genuine count/error for each of 19 sites, confirm original transfers and review deficiencies before merging Downloader with Rounder.
