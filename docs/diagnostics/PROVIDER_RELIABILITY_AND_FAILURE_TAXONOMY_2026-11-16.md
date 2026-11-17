# Asterion Downloader — current reliability and failure classification

**Updated with repair evidence on 2026-11-17.** This classification supersedes earlier stale matrices. See [the 17 November verified code and live-file repair checkpoint](REAL_REPAIR_CHECKPOINT_2026-11-17.md) for newer exact test receipts. Those PC successes do **not** change Android provider classifications without complete phone receipts.

## Current baseline (observed, not inferred)
- Asterion Control reports the paired Samsung **SM-S948B, Android 17** reachable; `com.azrael.pixivdumpsync.acceptance` installed and responding to its native diagnostic bridge.
- Native diagnostic of the **installed app** reports **versionName 1.3.70-acceptance, versionCode 99**, `process_response_verified=true`, `snapshot_scope=partner_process_only`, and **eight historical Android exits**. Its `engine_queue_state_verified=false`; the returned `videoflow.counts={}` and `recent_failed_items=[]` are **NOT a zero-error pass**, because this response does **not** verify a live transfer queue. An older native snapshot showed nine errors; no verified clearance of those errors exists.
- Current available device RAM **2,456,645,632 bytes**; code requires **4,000,000,000 bytes** to allow a second heavy webpage extractor. Therefore the second process remains **native HTTP only** under current admission logic.
- Installed version ≠ all modules tested. No claimed 1.3.70 Android full-original pass exists for the nine principal failures.
- The owner's latest comprehensive tests take precedence over isolated PC HTTP successes and old fixes. Later fixes may have been compiled/installed, but source changes are not promoted to passes without targeted phone evidence.

## Classification rules and evidence confidence
**Working and reliable** = named workflow the owner explicitly tested successfully; label `LAST_REPORTED` when not repeated after the current update. **Working and unreliable** = some owner-observed success and failures, missing UI parity, or regressions. **Not working** = requested final output fails under the latest reports (even if page or MP4 Range works). **Unverified** = no end-to-end evidence; not quietly assigned to reliable. Rows are **workflow-scoped**: Rule34 original vs poster, Archivebate listing vs transfer, Hentaibros historical successes vs reliable full output, Pixiv general sync vs provider original. The same provider can occur under distinct workflows and statuses without contradiction. The older nine Android error rows are not proven 1:1 with the nine listed providers.

## WORKING AND RELIABLE — last reported workflow, not current-release certification

| Provider/module | Exact scope | Evidence | Recency / confidence | Next requirement |
| --- | --- | --- | --- | --- |
| Toonily | ZIP comic download | ZIP completed in user's phone testing | User last-reported; not repeated on installed 1.3.70 | Protect original ZIP engine, chapter order, image count |
| MangaRead | ZIP comic download | ZIP completed in user's phone testing | User last-reported; not repeated on installed 1.3.70 | Protect original ZIP engine, chapter order, image count |

## WORKING AND UNRELIABLE — some actual successes, known regression or missing parity

| Provider/module | Exact scope | Evidence | Recency / confidence | Next requirement |
| --- | --- | --- | --- | --- |
| General sync | Sync | User says sync works; backfill previously needed fixes | Last user test; current 1.3.70 not confirmed | Prove repeated sync with no duplicate/missing originals |
| General backfill | Manual provider backfill | Initially failed, subsequently worked for some providers; delay/queued work remains reported | Mixed user tests; source patch unverified on current device | Measure tap→worker→full source crawl and retained cursor |
| Kuroha legacy | Artist backfill via legacy UI | Works via legacy UI, not via regular unified UI | Confirmed scope: legacy UI only | Reuse same working operation in unified UI before legacy removal |
| Rule34Video | Original video downloads | Owner reports originals work but posters are missing | User result + first-party MP4 byte-range on PC; current completion not rechecked | Protect direct original; repair posters and full receipt separately |
| Archivebate | Discovery/catalog | 24 cards and poster URLs were once indexed, then source error | Intermittent user test; current site age gate | Fix selected catalog/Continue while preserving indexed entries |
| Hentaibros | Some older selected downloads | Occasional historical success; current exact selected original unsupported/unproven | Historical partial only; full-transfer acceptance FAIL | Extract one real selected #wpfp_ source, reject preview/sidebar, verify full output |
| OnlyHentai | Some older selected downloads | Previously some sources worked and others failed | Historical intermittent only; full-transfer acceptance FAIL | Repeat same and different selected items, no wrong saves |
| Koikatsu cards | Card discovery/thumbnail | Cards displayed but were cropped; original aspect required | User UI observation; regression risk | Keep uncropped preserveOriginal across every card path |

## NOT WORKING — current requested outcome fails or has confirmed blocking bug

| Provider/module | Exact scope | Evidence | Recency / confidence | Next requirement |
| --- | --- | --- | --- | --- |
| Jav Guru | Exact selected video | HTTP 401/403 on Android | Device error | Capture allowed session/relay stage; no ad replacement |
| FullPorner | Exact selected full video | Generic watch extractor unsupported; three correct-player MP4 byte ranges on PC | Android failure + PC partial success | Use selected /player/ script/source → native file transfer → verify duration |
| JAVIDEO | Exact selected full video | Page/player selection unresolved, generic metadata timeout; visible MP4 may be ad | Android failure + PC timeout | Select active Streamtape iframe and verify correct original |
| Pornhub | Exact selected full video | Region/network-access denial or site gate | Device failure / environment-dependent | Explicit authorized network route and selected original receipt |
| Roshy TV | Exact selected full video | Beeteam368 original stream/mirrors visible, no resolved full media | Android failure + PC page proof | Resolve exact selected Kamehaus/original or legit hoster media |
| 3DHentaiX | Exact selected full video | Wrong-media danger: 6MB MP4 is not proved selected; generic unsupported | Device failure + PC byte proof | Only signed first-party post_id/nonce media, selected ID proof |
| HentaiHaven | Exact selected full video | Public verification shell, no original player | Device failure + PC site gate | Access authorized real episode page and media |
| UncensoredHentai | Exact selected full video | Selected nhplayer iframe unsupported by generic extractor | Device failure + selected iframe evidence | Resolve exactly nhplayer selected original |
| MP4Hentai | Exact selected full video | External Mirror/Upfiles landing never reaches verified downloadable bytes | Android failure + PC page proof | Complete hoster resolution rather than save landing HTML |
| HelloPorn | Exact original video | Selected VOE iframe and redirects, no full original | User failure + page evidence | Bind VOE /e/ to selected article and validate media |
| FreePornVideos | Complete video download | Metadata 3 formats and MP4 range work; 231,602,681-byte original timed out after 390 seconds | Real PC complete-file test FAIL | Instrument bytes/throughput, recover/resume, verify full file |
| Eporner | Selected video extractor | Current player signature changed | Exact bundled yt-dlp PC failure | Update player signature and verify selected full file |
| HQPorner | Selected video extractor | myxstudio → mydaddy nested chain, final original not verified | Real nested-player evidence | Resolve and validate only the selected nested player |
| Hentaibros | Reliable full original for every selected item | Partial historical success not sustained; generic unsupported, previews share DOM | Real PC failure + user regression | Require correct selected original, no fallback to ads |
| OnlyHentai | Reliable full original | Sporadic successes don't meet user reliability requirement | Owner's repeat tests | Prove repeatable full original with same item identity |
| Archivebate | Selected recording transfer | Recording 16458050 failed Python extractor; public Continue gate | Device + PC | Select exact authorized /watch/ player, full valid file |
| Rule34Video | Card posters/thumbnails | User sees blank posters despite working original route | Device UI failure; PC JPEG works | Fix selected-thumbnail referer/decoding; preserve original ratio |
| Pixiv | Current provider original downloading | Owner reports formerly working Pixiv stopped working | Latest user report; authenticated re-test required | Real authorized page and saved original, no duplicate corruption |
| Pixiv / Kuroha | Unified artist historical backfill | Legacy works; unified feature does not match it | Latest owner report | Unify existing legacy action and verify progress |
| X Following | Accurate following list | Unfollowed accounts imported/persisted as followed | User phone data-integrity failure | Reconcile authoritative user's follows; separate manually queued sources |
| Torrents | Selected actual download and public release | Owner reports total failure; code quarantines all finished files with no scanner/release | Owner phone + source confirmed | Seeded legal torrent, verified pieces + trusted verdict + explicit selected-file release |
| FreeWebNovel / Dual Cultivation | Complete EPUB | Real chapter 1 and chapter 1147 HTML readable; phone never produced EPUB | Owner phone + PC partial | Fix reader crawl/continuation then verify complete generated EPUB |
| Cosplay / Hentaibros galleries | Selected album and originals | HTTP 403, empty thumbnails, 'no supported public media listener' errors | Owner phone error | Reconcile selected album URLs, access gates, original count and bytes |
| CosplayTele / 4KHD / Kiutaku gallery | Full selected galleries | Various listing/thumbnail/original errors; one Kiutaku JPEG on PC is not full gallery pass | Owner phone failures + PC partial | Select exact album and download every original, count validation |
| PornPics / performer/model discovery | Correct selected model/performer | Unrelated content saved | Owner phone mismatch | Source ID↔performer↔album mapping and identity checks |
| Download engine | Parallel *webpage* jobs | Secondary extractor excluded below 4GB available RAM; current phone ~2.46GB | Source gate + live device memory | Two different webpages advancing simultaneously with bounded memory and no crashes |
| Download engine | Queue/Pause/Resume/Discard | Queued files stall with no active job; controls unclear, some discard unavailable | Repeated owner phone tests | State/lease recovery, visible active transfers, safe discard preserving originals |
| Downloader overall | Stability | Frequent crashes, historical Android process exits | Repeated owner tests; live bridge shows 8 historical exits, not attributable stack | Native crash traces and repeated high-load no-crash tests |
| Unified interface | Three legacy UIs parity | Existing legacy workflows not all integrated; user still forced to switch | Owner phone tests | Exact command parity before entry-point retirement |
| Pornhite / Pronhite | Unexpected redirect | User reports redirects to unintended providers | Owner phone | Reject redirect whose final publisher identity differs |
| HelloPorn / FreePornVideos / OMG | Duplicate / unwanted site entries | User asked OMG removal if duplicate; acceptance feature unwanted | Owner requested change; completion not confirmed | Deduplicate correct provider entries, preserve existing data |

## UNVERIFIED / INSUFFICIENT RECENT EVIDENCE — never treat as reliable

| Provider/module | Exact scope | Evidence | Recency / confidence | Next requirement |
| --- | --- | --- | --- | --- |
| Kiutaku single-image HTTP route | Complete original JPEG | 281,036-byte JPEG validated on PC | PC-only pass | Android gallery/export not proven |
| FreeWebNovel individual chapters | Chapter HTML | Two actual chapters returned full HTML with reader | PC-only pass | Does not prove EPUB |
| Rule34Video quality media route | MP4 byte ranges | Three 720/480/360 quality endpoints return HTTP 206 valid MP4 headers | PC-only pass | Full lengths and identity/playback not proven |
| FullPorner source qualities | MP4 byte ranges | Three /vid/ source URLs return valid MP4 header bytes | PC-only pass | Not correct completed video receipt |
| Other registered adult/video sites | Video downloads | NotFans, Cums, XXXI, WatchPorn, Camstreams, etc. lack selected-item full receipts in current tests | No current conclusive Android pass | Test each selected item; don't infer from catalog |
| Other manga, novel, cosplay, assets/3D providers | Complete output | No current named provider-specific repeated full receipts | Not enough evidence | Test per site, selected originals, counts, integrity |

## Nine mandatory video failures
These nine remain **NOT WORKING** for the precise scope of a selected, correct, fully saved playable original: **Jav Guru; FullPorner; JAVIDEO; Pornhub; Roshy TV; 3DHentaiX; HentaiHaven; UncensoredHentai; MP4Hentai.** Native earlier phone snapshot: 9 error-state jobs, only 8 individual records exposed, so job IDs must not be fabricated as a verified provider mapping.

## Issues as subcategories (no status inflation)
- `AUTH_OR_403 / REGIONAL_GATE / VERIFICATION`: Jav Guru, Pornhub, HentaiHaven, Archivebate.
- `EXTRACTOR_UNSUPPORTED / PLAYER_UNRESOLVED / HOSTER_UNRESOLVED`: FullPorner, JAVIDEO, Roshy, UncensoredHentai, MP4Hentai, HelloPorn, HQPorner, Eporner.
- `WRONG_MEDIA / SELECTED_IDENTITY`: 3DHentaiX, Hentaibros, inaccurate performer discovery.
- `BYTES_STALL / INCOMPLETE_FILE`: FreePornVideos 390-second 231,602,681-byte transfer fail; other sites lacking final verified bytes.
- `LISTING / THUMBNAIL / ASPECT`: Rule34 posters, cosplay galleries, Koikatsu crop.
- `SCHEDULING / PARALLEL / QUEUE`: backfill queue, stalled jobs, secondary heavy extractor unavailable, pause/resume.
- `DATA_INTEGRITY`: X false follows, wrong selected performer, Pixiv/Kuroha dedup.
- `OUTPUT_GENERATION / EXPORT`: FreeWebNovel EPUB, torrent final release.
- `REGRESSION / CRASH`: Pixiv provider stopped working, historical Android exits, repeated crash reports.
- `UI_PARITY / UNWANTED_MODULE`: legacy features missing from unified UI, duplicate OMG/FreePornVideos, unwanted acceptance feature.

## Evidence anchors and non-destructive acceptance
See [real-engine prebuild test](PREBUILD_REAL_ENGINE_VERIFICATION_2026-11-16.md) and [nine-failure repair register](../production/DOWNLOADER_FAILURE_REGISTER_AND_REPAIR_2026-11-16.md). Actual PC diagnostics: `D:\AsterionControlRelease\diagnostics\downloader_prebuild_20261116\`; phone native bridge command results `dl-current-status-20261116-61f3aa`, `dl-current-apps-20261116-61f3ab`, `dl-current-native-app-summary-20261116-61f3ac`, `dl-current-diagnostics-detail-20261116-61f3ad`. Do not export private cookies/tokens. No new app build was made to produce this document. Protect databases, signatures, originals, artist identities, working Toonily/MangaRead ZIP, legacy Kuroha operation, and native Koikatsu aspect.

**Release rule:** only an actual, selected correct file on the phone, with full bytes, openability, correct identity, no repeats and surviving restart, can upgrade a video provider to working and reliable. PC-only valid Range, article HTTP 200, first-party iframe, tests, or APK installation cannot upgrade it.

## Source repair checkpoint (after current reclassification)
- **Hentaibros selected-original protection implemented** at `SiteSpecificOriginalSources.kt`: when the specific `#wpfp_*` WordPress player publishes its selected original media, no secondary `<video>` from the same page wrapper is appended as a transfer fallback. This directly removes one wrong-media path; other source/media gates remain unchanged.
- New regression test `ReportedSiteSpecificOriginalsTest.hentaBrosDoesNotFallbackToSameWrapperUnrelatedVideo` asserts that the selected source outranks a separate playable 1200-second MP4 from the same `.video-player` wrapper.
- **Verified on the Windows repair worktree:** source/test commit `a0a07eece8dad7934c72913171b6b770986f7d4f`, Gradle `:app:testAcceptanceUnitTest --tests com.asterion.videoflow.ReportedSiteSpecificOriginalsTest --offline` **BUILD SUCCESSFUL**, exit code **0**. Pre-existing Kotlin nullable-call warning at `SiteSpecificOriginalSources.kt:482` remains; no compilation failure.
- **Not verified on phone, no APK built** after this fix; Hentaibros remains `NOT WORKING` for dependable selected full-original transfers, despite passing source regression tests.
