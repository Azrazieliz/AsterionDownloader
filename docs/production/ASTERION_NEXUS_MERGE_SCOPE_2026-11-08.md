# ASTERION NEXUS — CANONICAL FUSION AND MERGE SCOPE

Date: 2026-11-08
Product name: **Asterion Nexus**
Status: **SCOPE DEFINED — NOT IMPLEMENTED**
Canonical repository: https://github.com/Azrazieliz/AsterionDownloader (main)
Preserved donor repository: https://github.com/Azrazieliz/AsterionRounder
Decision: One native Android app; existing Downloader and Rounder features are to be preserved and integrated, not rebuilt or nested as APKs.

> This is a CONTINUATION and integration of existing work. Do not delete either existing app, repository, user data, queues, site configurations, reader assets, stored files, or cloud folders. Do not claim migration, installation, preview, streaming or remote operation success before on-device verification.

## 1. Canonical repository decision

**Retain Azrazieliz/AsterionDownloader as the long-term production source, Android build and release repository.** Rename the PRODUCT to Asterion Nexus, without renaming or deleting the existing repository during the migration.

Why: Downloader is already a conventional Kotlin/Gradle Android project with native source trees, Android manifest, persistent site/sync/background services, download history and transfer engines. This is the more suitable maintainable host. The Rounder repository currently assembles much of its Android project from source archive parts and an ordered set of CI hotfixes; that assembly must be inventoried and imported as real source modules, not by pretending Rounder's main branch already contains the latest app.

**Keep Rounder available and unarchived** as a donor, fallback and historical record until every agreed feature is migrated and phone-accepted.

### Existing change sets to preserve / reconcile

- Downloader main branch plus [unified-downloads PR #17](https://github.com/Azrazieliz/AsterionDownloader/pull/17). Existing Kuroha, Kyora and VideoFlow names may remain as *legacy code or stored-data identifiers*, but they are NOT three separate applications or the proposed Nexus navigation.
- Rounder [PR #14](https://github.com/Azrazieliz/AsterionRounder/pull/14): native TeraBox/provider/readers.
- Rounder [PR #16](https://github.com/Azrazieliz/AsterionRounder/pull/16): persistent recursive search.
- Rounder [PR #17](https://github.com/Azrazieliz/AsterionRounder/pull/17): organizer, duplicate evidence and source profiles.
- Rounder [PR #18](https://github.com/Azrazieliz/AsterionRounder/pull/18): continuous scrolling, signed updater.
- Rounder [PR #19](https://github.com/Azrazieliz/AsterionRounder/pull/19): visual duplicate comparison, manual keeper selection.
- Rounder [PR #20](https://github.com/Azrazieliz/AsterionRounder/pull/20): same launcher/header logo, thumbnail feedback and media controls. Donor branch: fix/rounder-unified-logo-previews-controls-20261108.
- Capture actual commit SHAs, assembled-source manifests, licenses, test results and proven Android build before selectively importing; the existence of a PR is not proof its behavior has passed device acceptance.

## 2. Product contract

**One file platform:** acquire → download → verify → store/upload → index → search/browse → preview/play/read → organize/reconcile → send to another Asterion app.

Nexus owns **file identity, provenance, storage location, transfer state, file integrity, preview, organization and file relationships**. Other Asterion apps keep ownership of the work they perform (recognition, art generation, sound creation, 3D conversion, game execution and device control).

### Five coherent surfaces (not five independent apps)

| Navigation | User-facing functions |
| --- | --- |
| **Library** (home) | TeraBox-first gallery, Open local file, original approved Rounder file cards, real thumbnails, recursive global search, filters, continuous scrolling, readers and players |
| **Acquire** | Websites/sources, search, URL intake, existing Pixiv/gallery/video adapters, per-site Sync, separate Backfill action, download queue control |
| **Transfers** | One status ledger for downloads, verified uploads, retries, pause/resume/stop, errors, destinations and remote reconciliation |
| **Organize** | File rules, deterministic routing, duplicate comparison/keeper selection, batch operations, storage reports and recovery |
| **Connections & settings** | TeraBox authentication, app source folders, local SAF permissions, integration registration, accessibility, preferences, security, signed Nexus updates |

Use context menus/actions for Send to Core/Art/Sound, Import in 2D→3D, Share, Save offline, Move, Copy and Details. Do not add redundant top-level tabs. Do not ship a page that merely links to another separate app screen without useful integrated behavior.

## 3. Existing functionality that must remain

### 3.1 Downloader — preserved in canonical project

- Source adapters, direct URL intake, Pixiv artist following/watchlists, galleries/cosplay sources, video extraction where supported, and user-added sites. Preserve real site compatibility; generic/best-effort scraping must not be advertised as a complete dedicated adapter.
- Existing downloader databases, completed files, source configuration, cookies/authorization, saved site history, exclusions/filters, backfill cursors, Live sync, scheduled work, and working request cancellation.
- **Per-site Sync and independent Backfill actions.** Backfill must be an actionable operation, not just an on/off toggle. Stop/pause/retry must be possible and accurately reflected.
- Proper user-facing queue with success/failure/retry, correct filenames and ordered previews; explicit actual downloaded, locally verified, upload started, remote verified, indexed states.
- TeraBox upload pathways are not interchangeable: keep the official Open Platform and unofficial NDUS/web integrations separate until their exact authorization and behavior are proven. No Round Sync handoff may count as upload verification.
- Former Kuroha/Kyora/VideoFlow are *merged internal functionality*, not standalone Nexus applications or automatic cloud source identities. Existing folders and database names can survive for backward compatibility.

### 3.2 Rounder — imported without functionality loss

- TeraBox tree access, SAF local file picker and persisted grants, optimized browsing, readers and media players; preserve actual supported PDF/EPUB/comic/archive/Office/3D preview implementations and document known format limitations.
- **Global recursive indexed search** across all descendants with persisted incremental index; must work with hundreds of thousands of cloud files without a full reindex on each query.
- **User-approved original card dimensions/layout are frozen**. No unauthorized card redesign. Fix loaded thumbnail overlays, stale URLs and failure states *within* that design.
- Correct thumbnail caching/proxy, meaningful folder previews without fake image placeholders, incremental paging at the provider boundary and **continuous page-free scrolling** in the UI (no 1/502 page navigation).
- Native video/audio players with reliable seek/range handling, visible buffering/failure reasons, recovery/cancel, Android Back/orientation handling. This is a **device acceptance gate**, not a completed reliability claim.
- Deterministic route proposals, file-source mapping, collision-safe moves, read-back of destination before success, duplicate comparison with thumbnails, explicit keeper selection and safe deletion only after confirmation. **No automatic duplicate deletion**.
- Rounder's signed in-app updater must become the ONE verified Nexus update mechanism rather than leaving separate Rounder and Downloader update feeds.

### 3.3 New shared infrastructure

1. **Unified metadata registry:** stable file ID, original URI, TeraBox path, content hash when known, MIME, size, timestamps, provenance/source application, source URL, collection, tags, dependencies, output versions, offline availability, permissions and scan status.
2. **Transfer receipt ledger:** durable request IDs and atomic states: QUEUED, RUNNING, PAUSED, FAILED, LOCAL_VERIFIED, UPLOAD_PENDING, REMOTE_VERIFIED, INDEXED. Never confuse a handoff/request with a completed transfer.
3. **Incremental search and preview services:** account-scoped persistent index, cached thumbnails, query cancelation, memory/CPU throttling and background scan checkpoints. No repeated full-library enumeration on search.
4. **Deterministic organizer:** owner-configured folder origins, extension/MIME and explicit metadata routing, dry run, collision handling, per-source opt-in automation, batch stop/retry; unknown provenance remains unknown.
5. **Duplicate evidence and cleanup:** verified hash+size, distinctly marked weaker name/size possibilities, image side-by-side comparison, keep-original selection, selected-space estimate, item-level confirmation and backend verification. Zero automatic deletes.
6. **Asset relationship/provenance manager:** link reference image → generated image → 3D output → textures/animation → package; allow multiple versions and export manifests without copying or corrupting originals.
7. **Recovery and integrity:** interrupted operation replay, checksums, storage reconciliation, provider errors, safe backups, import/export receipts, schema versioning and rollback.
8. **App integration registry:** explicit app identity, accepted MIME/URI types, supported import/export actions, permissions and operation outcomes; no fake connected statuses.
9. **Single secure settings/updater subsystem:** encrypt sensitive credentials, least-privilege URIs, correct Android permissions and signed in-place update verification.

## 4. Cross-app interfaces — Nexus as asset hub, not substitute

| Application | Nexus responsibilities | Responsibilities retained by specialized app |
| --- | --- | --- |
| **Asterion Core** | File location/URI, selection/export, taxonomy-result storage when supplied | Recognition, character knowledge and taxonomy AI |
| **Asterion Art** | Reference/output registry, projects, versions, file handoff | Image creation and editing, model conditioning |
| **Asterion Sound** | Audio file registry, preview, output versions and sharing | Audio generation, composition and processing |
| **Asterion 2D→3D** | Image/model/texture relations, conversion input/output files and package manifests | Conversion, Blender/PC processing |
| **Asterion KK / Koikatsu Android** | Explicit imports/exports for cards, scenes, mods and assets when supported | Game runtime, saves, mod loading and compatibility |
| **NoWorld — False Horizon** | Traceable delivery of verified source images/models/audio and dependency manifests | Unreal authoring, admissions, game packaging and tests |
| **Adaptive Study** | Source PDFs/course file storage and permissioned open/export | Course parsing, reading modes, study tools, mind maps |
| **Asterion Control** | APK file metadata/transfer status and requested install handoff | Device control, ADB/PC remote actions and installer orchestration |

Prefer versioned explicit Android intents and content:// URI interfaces with time-limited grants. Never assume another installed app exposes private databases or files. Integration is complete only when a real file and an outcome receipt can be exchanged successfully.

## 5. Critical signing, package ID and privacy constraints

**Preferred Android application ID:** Preserve Downloader's existing production identity **com.azrael.pixivdumpsync** if—and only if—the installed production app's private signing key is available and an in-place Nexus update is cryptographically compatible. Product name/logo may change to Asterion Nexus without changing application ID.

**Important existing blocker:** [Downloader original-signature upgrade documentation](https://github.com/Azrazieliz/AsterionDownloader/blob/main/docs/ORIGINAL_APK_UPGRADE_SIGNING.md) records a historic Kuroha signature mismatch and states the original signing key might be unavailable. GitHub CI debug signing is NOT a substitute. Compare the *actual installed* Downloader and Rounder package identifiers, signing certificates and version codes before selecting the upgrade path.

If signing continuity cannot be established, decide a **new Nexus package ID only with an explicit export/import migration and user approval**. Do not overwrite, silently uninstall, or clear either existing app. Keep both independently usable until migration is verified.

Android app sandboxing prevents Nexus from reading Rounder's private databases or Android Keystore secrets simply because repositories were merged. A private-data migration needs an owner-approved export or authenticated bridge, or metadata reconstruction from cloud when export is impossible. Never copy NDUS cookies, auth tokens or private keys into GitHub, CI logs or public files.

**Legacy filenames/folders must survive:** Downloads/Kuroha, Downloads/VideoFlow, Downloads/Nocturne*, /Nocturne/Kuroha, /Nocturne/Kyora etc. are historical data paths, NOT evidence of separate active apps. Do not auto-rename or delete these files.

**Primary cloud remains TeraBox; local Open file remains available.** Cloud moves require collision-safe verification. Deletion is manual and confirmation-based. One authenticated update channel for the ultimate Nexus package; Android installation still needs user consent.

## 6. Visual and ergonomics acceptance

- Exactly one approved Nexus launcher/header logo asset. No different star marks in launcher vs toolbar.
- Compact mobile header; no oversized branding, repeated “Your library” hero or redundant “Open collection” captions.
- Preserve approved existing Rounder file cards and dimensions; don't silently redesign them. Loaded real previews must never have fallback symbols overlaid.
- Consistent icon geometry and interaction states across navigation, downloads, library, readers, players, organizer, error dialogs.
- Real visible loading/retry/error/empty states; no decorative fake controls, indefinite progress, or raw debug/MD5 byte lists as the main duplicate manager.
- Use TeraBox and local file resources responsibly: paginate provider fetches, virtualize rendered content, cache thumbnails; do not load 300,000 images into memory.
- Accessibility: touch sizes, text scaling, contrast, Back actions and portrait/landscape video behavior.
- Independent background worker limits so indexing does not block live downloads or playback.

## 7. Implementation stages and release gates

| Stage | Deliverable | Required evidence |
| --- | --- | --- |
| N0 — Inventory | Preserve donor assembled source, PR SHAs, existing APKs/signers, databases, URI permissions, downloads, TeraBox receipts and dependencies | Baseline manifest + rollback snapshot |
| N1 — Canonical host | Native Nexus feature boundaries in Downloader repository; no source or data loss | Build/tests + original site adapters still operational |
| N2 — Unified interface | Consistent approved logo/control system and cohesive navigation with ORIGINAL Rounder cards | Real device UI review, no placeholder regressions |
| N3 — Acquire/Transfers | One site/URL intake, per-site Sync/Backfill, durable transfer ledger and remote verification | Actual source → local → cloud receipts and retry/cancel |
| N4 — Library/Viewers | Rounder TeraBox and native reader integration, persistent recursive index, image/audio/video/3D preview support | Deep search on 300k+ items, actual phone reading/streaming |
| N5 — Organize/Assets | Deterministic routing, duplicate comparison/keeper selection, safe moves, file relationships and recovery | Real provider operations + no destructive surprises |
| N6 — App bridges | Versioned explicit file interchange with Asterion Core, Art, Sound, converter, game/study apps and Control | Real import/export receipt per enabled integration |
| N7 — Migration | Signed identity or approved export/import flow and reconciled user data | Verified before/after data counts and rollback |
| N8 — Cutover | Signed Asterion Nexus release replacing overlapping app tasks | Full phone acceptance before retiring old app entry points |

**No artificial time estimates, no unrelated rebuilds, no unverified “finished” claims.** Report each deliverable as NOT STARTED, IMPLEMENTED, CI VERIFIED, DEVICE VERIFIED or BLOCKED.

### Functional acceptance checklist

- Global TeraBox recursive child search returns actual deep results without rescanning everything; stable for 300k+ files and resumable after interruption.
- All existing Downloader source adapters, user-added sites, automatic sync, manual backfill, filters, queue state and saved local data survive.
- Cloud upload and server-side file operations have **verified destination receipts**; no success based only on requests.
- Approved card display and loaded image thumbnails work on phone, as do reader opening, video buffering/seek/retry, folder navigation and continuous scrolling.
- Duplicate comparison displays actual previews and verified hashes; deleting a selected copy never removes the designated surviving copy.
- All enabled cross-app actions truly import/export at least one file and report their outcomes.
- User data migration, signers, credentials and Android permissions are verified non-destructively; a usable rollback is available.
- One matching identity, one consistent UI, one signed update path, one Nexus APK.

## 8. Explicit exclusions

- No revival of old Kuroha/Kyora/VideoFlow as standalone applications or separate Nexus navigation.
- No automatic AI tagging required for sorting; Core remains optional and specialized.
- No nesting two APKs, wrapping EXEs, or embedding one old app as an inert tab/WebView to claim fusion.
- No game engine, Blender runtime, 3D generation model, audio generation model or ADB controller inside Nexus.
- No silent full cloud mirroring, destructive migration, automatic duplicate deletion or unapproved card redesign.
- No deletion or archival of Rounder/Downloader repositories until migration is accepted, and no deletion by default even afterward.

## 9. First implementation step and current state

**This scope document is the only action being committed now.** Implementation of Nexus has NOT begun by virtue of creating this file. The next implementation commit should inventory actual installed APK signers and private-data migration possibilities, freeze both original apps, identify exact Rounder assembled-source outputs and begin a versioned, non-destructive provider/index module inside Downloader. Keep the donor source and installed old APKs until Nexus reaches N8 acceptance.

**Final repository decision:** Production host **Azrazieliz/AsterionDownloader**, product name **Asterion Nexus**; donor/fallback **Azrazieliz/AsterionRounder**.
