# Asterion Downloader — module agents, original receipts and Nexus/Core decision

Date: 2026-11-10
Source branch: `work/downloader-site-adapters-errors-20261110`
Approved UI checkpoint: `checkpoint/downloader-v1.3.48-ui-approved-20261110`

## This round — implemented in code

1. Preserved two independent VideoFlow Android transfer processes using SQLite atomic claims and leases; they run separate Python runtimes.
2. Retained `ModuleOriginalAgents` two-worker pools for indexed media modules. These dispatch actual original-file requests with SQLite conditional claims, not merely two website-indexing agents.
3. Retained two workers for EPUB generation/verified book archives, two comic ZIP workers with atomic claims, two Koikatsu PNG workers and two concurrent MP4Directs series lanes with strictly ordered volumes inside each series.
4. Added `OriginalFileReceipt` to examine actual downloaded file bytes rather than accepting Android DownloadManager `STATUS_SUCCESSFUL` as content success. Rejects HTML/access pages, missing/truncated ZIP/EPUB/APK central-directory trailers, wrong image/video/asset signatures and unreadable saved files. Uses bounded reads without loading complete large originals into memory.
5. Changed `DirectDownloadRetry` to validate before completing a durable dedup claim. HTML/access responses require intervention rather than blind repeated requests; other invalid responses have bounded automatic retries.
6. Changed `ModuleOriginalAgents.monitor` to mark `SAVED_RECEIPT` only after file validation; invalid files become `BAD_RECEIPT`.
7. Changed existing Android `RounderDownloadCompleteReceiver` to validate DownloadManager completion before initiating Rounder handoff.
8. Changed `RounderDispatch` MediaStore scan to reject invalid originals in Downloader-owned folders before passing them to Rounder/TeraBox. A cloud upload is never implied by a valid local file.
9. Kept approved Downloads card structure but distinguishes verified originals from unverified transport completion and malformed files; OPEN FILE now checks the actual original.
10. Corrected `BackfillAgents.submit`: concurrent requests for one provider now share the same pending Future until the *real scan and requested reruns* finish. Removed the fake instantly completed task that could cause false end-of-scan behavior.
11. Added pure-unit regression tests for HTML masquerading as EPUB/PNG/MP4, ZIP EOCD verification and image/video signatures.

## Existing site-specific work preserved

- Eporner official API/catalog and authorized official-player metadata.
- OMG.xxx verified domain migration to OMG.porn with source identity preservation.
- Actual Jav Guru numbered post routes, JAVIDEO release-ID routes and XVideos dot-ID routes.
- WatchPorn direct source with independent full-runtime checks.
- HentaiBros provider catalog with authentic source-owned covers.
- Reproducible per-site probes and explicit provider errors for unsupported extraction, 403, rate-limit 429, and redirect behavior. Do not equate catalog visibility with a full-original transfer.

## Test results and remaining acceptance

- Windows Gradle `:app:testPilotUnitTest :app:assemblePilot --offline`: **BUILD SUCCESSFUL** for the latest original-receipt/agent scheduler changes.
- Assembled test package currently remains `com.azrael.pixivdumpsync.pilotagents`, versionCode 79, versionName `1.3.50-pilot`.
- App's source is implemented; real Samsung concurrent completion for every module and all named sites is **not verified**.
- No new APK should be mislabeled as a data-preserving update without the exact installed package ID, matching signing certificate, higher versionCode and confirmed installer result.
- Existing historical previews, queues, local downloads and cloud paths must not be deleted or replaced to force a build update.
- Rate-limited, authentication-required, DRM-protected or inaccessible providers remain genuinely blocked until valid authorized site access or supported first-party extractor is available; never fake a save receipt.

## Proposed Core expansion of canonical Nexus scope — not yet implemented

Canonical current document `docs/production/ASTERION_NEXUS_MERGE_SCOPE_2026-11-08.md` on `main` defines Downloader + Rounder as the single Asterion Nexus host and Asterion Core as a separate specialized app with an explicit file bridge.

**Recommendation:** expand Nexus's optional feature set to include Core's existing Gallery/Recognition/Fusion taxonomy capability once its actual implementation and stability are inventoried and accepted. Reuse Core code/data; do not rebuild recognition, rebuild gallery cards or recreate taxonomy. One shared file registry/hash/provenance; Downloader acquires, Core identifies/reviews, Rounder stores/verifies. The Core inference engine runs in an independently recoverable service/process with resource limits, not in the download/streaming UI or Rounder file operations. Mark uncertain recognition REVIEW, never automatically rename/move/delete a valuable original based only on an unverified AI output.

Before changing the canonical exclusion of embedding Core, get explicit scope approval. Keep the legacy Core app usable until a non-destructive identity/taxonomy/gallery migration with device receipts has passed. Retain one final Nexus signing identity, updater, approved file cards and current storage paths. This proposal does **not** claim Core has been integrated.
