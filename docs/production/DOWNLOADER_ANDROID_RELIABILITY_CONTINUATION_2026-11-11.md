# Asterion Downloader — Android reliability continuation (2026-11-11)

## Canonical work
Continue branch `work/downloader-site-adapters-errors-20261110`. The approved Home/gallery/Downloads visual design is frozen; this work corrects transfer semantics, provider identity and runtime failures without a UX redesign. Preserve all existing user data and Rounder integration contracts. This document records implementation, not Samsung acceptance.

## User-reported failures
- Thirteen providers display errors; Pornhits/OMG provider redirects need trustworthy attribution, and HentaiBros and cosplay sources have not completed tested original downloads.
- Automatic performer lookup creates false follows from keyword matches.
- Koikatsu original cards are being visually cropped.
- Download queue appears idle or serial despite two worker processes/lanes.
- App crashes repeatedly on Android. Full device crash trace not supplied.

## Changes committed to this branch
- `ModuleOriginalAgents.kt`: paused/failed DownloadManager records no longer occupy the two *active* module-transfer slots; always release lane activity flags from worker finally blocks.
- `UnifiedMediaGrid.kt`: model/Koikatsu thumbnails preserve aspect ratio with FIT_CENTER. Other approved card presentation unchanged.
- `TransferService.kt`: report launch success if either independently isolated VideoFlow service starts. VideoFlow primary and secondary retain SQLite atomic claims.
- `AsterionApplication.kt` and `AndroidManifest.xml`: install crash capture on each Android process startup rather than waiting for Home activity.
- `WatchPornPanel.kt`: confirm same-domain HTTPS first-party performer profile, canonical path and matching visible identity; reverify before follow. Reject unconfirmed keyword/title results.
- `UnifiedDownloadsUi.kt`: existing Start/Resume controls launch the indexed-original and due-retry engines asynchronously, rather than only VideoFlow/X/Books. Successful DownloadManager status does not equal saved original until file-type/size validation confirms it.
- `KyoraCreatorPreviews.kt`: coalesce many simultaneous provider-preview callbacks to avoid repeatedly rebuilding the large gallery and exhausting UI resources; initialize Android main Handler lazily for JVM unit tests.
- `FullLengthVideoPolicy.kt`, `VideoDb.kt`, `SourceIndex.kt`, `TransferService.kt`: explicit user selection may proceed after metadata-runtime and preview-marker checks without a preexisting full-scene reference; automated website crawling retains strict hold. Explicitly tapping a previously held indexed item promotes only that item to a user-owned queued job. Preserve actual file verification and reporting: this does not claim independent proof of a complete publisher cut.
- Existing codebase unit tests extended for agent-slot behavior, verified performer profiles and explicit user media selection.
- GitHub Actions `asterion-downloader-sitefix.yml` still locks the approved visual layout while permitting narrowly scoped download-button and receipt-data fixes.

## Verification status
Earlier changes passed GitHub Actions Android compilation, both pilot/acceptance unit-test suites and APK assembly. The latest set of changes must be evaluated against the corresponding newest GitHub Actions run; do not infer success from an older green build. The 2026-11-11 preview-coalescing implementation initially exposed a JVM-only Android Handler initialization failure; the Handler is now lazy. The frozen-layout CI guard was also corrected to allow an asynchronous queue launch.

**Still not proven:** concurrent downloaded bytes and two simultaneously verified original-file receipts on Samsung Android; successful downloads from every reported adult/hentai/cosplay source; reproducible elimination of user-reported crashes; data-preserving installation using the original persistent signer; Rounder/TeraBox end-to-end verified cloud copies. None should be marked accepted without live evidence.

## Acceptance gates
1. Build the final branch with passing `testPilotUnitTest testAcceptanceUnitTest assemblePilot assembleAcceptance` and checked signatures; report exact commit and workflow run.
2. Install using a package/signing route that preserves current user data, or clearly identify a separately installed pilot (never imply that pilot updates the existing app).
3. On the Samsung phone, demonstrate **two distinct active transfers** progressing bytes at the same time and producing two valid file receipts. Test per-module queues and failure/retry behavior.
4. Download and verify representative originals from each reported problem site. Distinguish provider account/redirect/HTTP errors from app extraction failures, and never relabel other-provider media as the requested site.
5. Confirm real first-party performer identity before any new follow; retain unverified legacy rows disabled until verified.
6. Display Koikatsu cards uncropped and run UI scrolling/crash soak tests with all installed providers. Capture stack trace for any remaining crash and patch its actual cause.
7. Validate Rounder and TeraBox reconciliation *after* verified local original files, not after job handoffs.


## 2026-11-11 follow-up — duplicate provider and real-site diagnostic audit
- **OMG retired, evidence verified:** on both public landing pages, 34 separate numerical video identifiers were observed and all 34 matched (34/34 overlap). Separate URLs on `www.omg.porn/fr/` and `www.freepornvideos.xxx/` do not represent a distinct media catalog. Remove the OMG source row and disable old `omg.porn` and `omg.xxx` source watches/queued originals; preserve historical downloads and media. Pornhits remains retired. Approved Aloha catalog now 41 (17 ADULT); all prior unrelated sources preserved.
- **Actual Android Acceptance v1.3.51 observations:** Porn page: 19 providers, 12 with thumbnails, 7 source errors; CUMS/Jav Guru/OneJAV/XNXX HTTP 403; JAVIDEO no supported listing media; PornTrex unexpected redirect; EPORNER thumbnail-listing error. Hentai page: HentaiBros had 30 indexed records but zero previews. Books page: Dual Cultivation EPUB reported `Reader chapter number not visible`, 0 chapter texts cached. Downloads: 0 active, 2 failed, 1 saved ZIP. These observations are from the installed older build; they do NOT prove that the new repairs are running on the phone.
- **First-party public provider HTML checks via connected Windows worker:** HentaiBros homepage HTTP 200 and 31 HTML images, real `article > a > div.post-thumbnail img.video-main-thumb`; direct post path `/muramata-san-no-aijou-episode-2/` is a legitimate single-slug video. JAVIDEO homepage HTTP 200, 45 HTML images; actual movie links are siblings of an `img` within `.card__cover`, and some response images are only `/images/default-cover.*` placeholders. Archivebate public front page exposes logos/navigation but no accessible gallery images. PornTrex HTTP 403 from Windows worker despite a separate web client accessing its public catalog; do not misclassify it as an automatic download success. OneJAV HTTP 403 from independent web request.
- **Corrections:** native video-page classification now accepts provider-specific HentaiBros/JAVIDEO routes in public preview and indexed item discovery; sibling video card links are indexed as `video` without claiming a fake placeholder; actual HentaiBros first-party covers can render, generic default-cover art is rejected; HTML fixtures added as regression tests.
- **Mandatory acceptance still missing:** successful saved originals for the failing providers and two independently progressing simultaneous transfers on Samsung; an installed compatible owner-signed build of the latest branch; complete crash soak test. Do not call this an on-device fix until those conditions pass.
