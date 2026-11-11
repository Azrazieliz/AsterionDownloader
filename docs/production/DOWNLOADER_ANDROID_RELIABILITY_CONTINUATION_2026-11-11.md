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
