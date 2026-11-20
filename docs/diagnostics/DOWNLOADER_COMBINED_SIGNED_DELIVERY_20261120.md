# Downloader — combined owner-signed Android delivery / factual checkpoint

Date: 2026-11-20. Release source frozen at **58240879617b5636496e286cf1a54cb55208f7db**.

## What is verified (not inferred)
- GitHub one-build preflight at **5824087**: **success**, `verifyFrozenVideoProviders`, Pilot and Acceptance source unit tests.
- Isolated owner's Windows PC source checkout `D:\AsterionDownloaderFinal20261120`: exact **5824087**, offline Gradle Pilot+Acceptance tests **passed**, exit 0.
- Windows retained-key acceptance build **passed**, exit 0. Package `com.azrael.pixivdumpsync.acceptance`, variant 1.3.76.
- Retained signer SHA-256: **8914af1b2713e1d7e93c52abfddf11cbc6bba8d7057906161185a4216dcc0c19**. No debug certificate or signature replacement.
- APK **54,912,065 bytes**; SHA-256 **ffbf5d5365f05492822b1e10e7790b66db9705ad5653c001ecb004881061f59f**.
- **Android transfer checksum verified**, through existing private Wi-Fi/Asterion Control to Samsung `SM-S948B`: `Downloads/Asterion Control/AsterionDownloader-v1.3.76-Combined-OwnerSigned-20261120.apk`, content URI `content://media/external/downloads/1000149563`.
- Receipt explicitly reports **`installed:false`**; an APK present on the phone is NOT proof of installation or end-to-end successful downloads.
- PC temporary output APK was cleaned after verified phone receipt; original working sources untouched.

## Source repairs included in the delivered APK
- Selected TaiAV first-party `/api/getmovie?type=<published-quality>&id=<selected-movie>`, exact first-party VOD playlist, ENDLIST/segment runtime verification, preview route exclusion.
- Windows real public selected TaiAV VOD proved HTTP 200, 782 EXTINF segments totaling 3124.6 seconds, 1 VOD playlist and ENDLIST; Android full-original download remains unverified.
- UncensoredHentai exact NHPlayer-selected first-party route; OnScreens/LiveCamRips exact selected child-player network routing.
- Full-video sync: independent known-original duration checked before favored-host shortcut; short unmatched automated preview clips not published as complete originals; manually chosen genuinely short work retains intentional behavior.
- Video cancellation: exact, user-dismissed private staging cleanup; delayed cleanup for running cross-process transfers; SHA-verified redundant legacy stage only; never delete paused, retriable, MediaStore-saved originals.
- Torrent cancel/discard controls use isolated native torrent engine, not generic process reroute; quarantine remains intact.
- Existing search improvements, clean provider display names, Pixiv thumbnail recovery, CosplayTele mixed photo/video albums, performer-proof constraints, gallery controls, original provider labels beside Downloads thumbnails, indexed poster recovery, real module media titles/durations when published, and first-party model-file lookup.
- No new provider additions; **Porneec remains unchanged**; previously explicitly rejected publishers must not be reintroduced. No UI layout redesign without owner approval.

## Explicitly incomplete: do not mark fixed
1. Installation confirmation and **Android full-file receipts** for this APK are pending.
2. JAVideo selected player: PC genuine click reached its exact `play.javideo.net` iframe but CDP same-process isolated-world call timed out; a full media stream was NOT obtained. Still broken.
3. OnScreens selected VOE/Dood media, LiveCamRips 401/403, some JAV Guru titles, UncensoredHentai Android real transfer and TaiAV Android full original still require selected-file verification, not only HTML/poster/test fixture proof.
4. Creator/performer matching requires correct first-party profile evidence and **attributed** media, not title-only matches. Internet-wide search quality/coverage and paid/stub results remain acceptance work.
5. Preview/title/duration/placeholder accuracy, Gallery/Pixiv/cosplay true originals and progress/cancel actions, torrent native stability and archivebate false error require on-phone acceptance.
6. User removed their 10 GB app data already. **Do not reopen that old storage clean-up issue or delete their current data.**

## Continuing implementation rules
- Source of truth: repair branch `repair/downloads-nine-providers-backfill-diagnostics-20261116`.
- Protect working video providers; restore exact previous byte-producing routes before rewrites; no speculative additions, generic unrelated player fallback or misleading partial previews.
- Owner explicitly requires **one combined release**, signed with the retained original certificate and moved by existing Asterion Control. Never conflate APK transfer with installation.
- If phone app switching would risk unsent drafts or permissions, stop at verified transfer and require explicit Android package-installer confirmation; never uninstall or clear data.
