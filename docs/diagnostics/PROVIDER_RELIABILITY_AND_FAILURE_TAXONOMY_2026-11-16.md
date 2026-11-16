# Asterion Downloader — preserved reliability classifications and failure subtypes

Updated 2026-11-16; owner-reported Android tests, private Asterion Control native receipts, and real-PC prebuild probes. This is the acceptance register, **not** a declaration that a patched APK has been validated. Existing source/data/signature/working methods must be preserved. Status applies to named scope, not every sub-feature of a provider.

**Classification rule:** Reliable = multiple previously successful owner tests for named workflow, with no known failure *in that workflow*; unreliable = historical success but regression, intermittent transfers, partial UI or media bytes; not working = core requested output broken or unsafe; unverified = no sufficient real selected-item test. No HTTP 200/metadata/206 or static parser test is a complete playable original receipt. If failure is later resolved, update status only with selected identity, full saved bytes, playable duration, final destination and restart receipt.

## Working and reliable (reported verified use) — 3 named scopes

| Module/site | Issue subtype | Evidence / observed behavior | Required acceptance | Evidence type |
|---|---|---|---|---|
| Toonily — ZIP | `COMIC_ARCHIVE` | User phone: ZIP succeeds | Preserve full chapter order, image counts, ZIP integrity; add regression | user_report |
| MangaRead — ZIP | `COMIC_ARCHIVE` | User phone: ZIP succeeds | Preserve this ZIP path | user_report |
| Kuroha legacy artist backfill, legacy UI only | `LEGACY_ONLY` | User phone: artist backfill works via legacy interface | Reuse exact working action in unified UI; do not delete legacy until parity | user_report |

## Working and unreliable (partial/intermittent or regressed) — 10 named scopes

| Module/site | Issue subtype | Evidence / observed behavior | Required acceptance | Evidence type |
|---|---|---|---|---|
| Pixiv sync / followed artists | `REGRESSION_AUTH_OR_SYNC` | Worked in prior tests, stopped again in later phone tests; follow pagination can mask failures | Validate authenticated first original and follow pagination, retain IDs and files | user_report/source |
| Pixiv/Kuroha historical artist backfill | `DEFERRED_BACKFILL_OR_DEDUP` | Legacy works, unified broken; backfill delayed and no linked legacy folder | Test immediate worker start + history discovery + dedup with authorized existing folders | user_report/source |
| Rule34Video overall | `POSTER_MISSING_OR_MEDIA_RECEIPT` | Owner reports originals download; thumbnails absent. Public 720/480/360 originals return valid 206 byte ranges, not completed receipt | Preserve downloads, repair card poster/referer and require full file validation | user_report/real_PC |
| FreePornVideos | `EXTRACTOR_REGRESSION_AND_TRANSFER_TIMEOUT` | Three formats + 32KiB 206 verified; real 231,602,681-byte 480p transfer failed at 390s | Measure actual byte-progress/throughput; resume/retry range; verify full duration, same ID | real_PC |
| Archivebate catalog/player | `SITE_GATE_AND_SELECTED_EMBED` | 24 cards once found but later source error; recording 16458050 Python extractor fail; public Continue gateway | Reproduce official Continue/selected /watch/ and resolve Mixdrop exact player, not generic webpage | user_report/real_PC |
| Hentaibros | `SOURCE_IDENTITY_OR_EXTRACTOR_UNSUPPORTED` | Historical sporadic success; public DOM includes 47KB/1MB/3.5MB unrelated-looking media; generic unsupported | Only single selected wpfp data-item original; full byte/duration/identity verification | user_report/real_PC |
| OnlyHentai / OnlyHentaiStuff | `SPORADIC_PROVIDER_TRANSFER` | Some episodes reportedly work, others fail | Do not mark provider reliable until selected-episode full-file repeat tests | user_report |
| Kiutaku gallery | `GALLERY_LISTING_OR_UI` | PC retrieved one complete real 281,036-byte mitaku.net JPEG; phone album/discovery not established | Verify cards/album count and complete selected gallery end-to-end | real_PC/user_report |
| Koikatsu card rendering | `THUMBNAIL_CROP` | Cards sometimes cropped although original aspect must be preserved; source uses preserveOriginal for specific cards | Recheck every UI path on phone; protect no CENTER_CROP | user_report/source |
| General site sync / scheduled backfill | `SCHEDULER_QUEUE` | Sync sometimes works; manual backfill defers or becomes blocked by transfers; source patches not device-proven | Measure tap-to-active worker and completion, automatic history consistency | user_report/source |

## Not working (full task fails) — 29 named scopes

| Module/site | Issue subtype | Evidence / observed behavior | Required acceptance | Evidence type |
|---|---|---|---|---|
| Jav Guru | `AUTH_OR_403` | Android selected transfer HTTP 401/403; live full original absent | Prove authorized exact selected /searcho → hoster → manifest; classify 403 by stage | device |
| FullPorner | `EXTRACTOR_UNSUPPORTED` | Generic player unsupported; selected /player/9509933/14 offers three valid source byte-ranges (400MB/689MB/1.23GB) | Use exact selected player native MP4, correct media and complete file receipt | device/real_PC |
| JAVIDEO | `PLAYER_UNRESOLVED_OR_TIMEOUT` | Generic extractor 35s timeout; DOM initial MP4 could be advertisement | Only selected Streamtape frame and actual original; disallow advert source | device/real_PC |
| Pornhub | `REGIONAL_NETWORK_GATE` | French/unknown route rejected; no selected media | Report authorized connection and publisher gate; do not fake route | device/real_PC |
| Roshy TV | `PLAYER_OR_HOSTER_UNRESOLVED` | Beeteam368 Original Stream and mirrors published but no verified file | Selected player/hoster → real content; never hoster landing HTML | device/real_PC |
| 3DHentaiX | `WRONG_MEDIA_RISK` | 6MB HTML5 MP4 exists but cannot be attributed; generic unsupported | Only post ID + nonce signed publisher playback and full byte receipt | device/real_PC |
| HentaiHaven | `VERIFICATION_GATE` | Public HTML verification shell, no original <video>; generic unsupported | Authorized selected episode and exact source after gate | device/real_PC |
| UncensoredHentai | `PLAYER_UNRESOLVED` | nhplayer iframe, generic unsupported | Exact selected iframe → media/manifest and full transfer | device/real_PC |
| MP4Hentai | `EXTERNAL_HOSTER_NO_FILE` | Article HTTP 200; Mirror/Upfiles landing never becomes verified media | Resolve final authorized external hoster and selected original | device/real_PC |
| HelloPorn / VOE | `EXTRACTOR_UNSUPPORTED_OR_PLAYER` | Published selected VOE iframe but no validated file; earlier redirects | Selected VOE /e/ → full file rather than unsupported HTML | user_report/source |
| Eporner selected video | `PLAYER_SIGNATURE_CHANGED` | Actual bundled yt-dlp reports extractor signature changed | Adapt to current publisher player, then verify whole selected original | real_PC |
| HQPorner | `NESTED_PLAYER_UNRESOLVED` | myxstudio → mydaddy nested player, complete media not resolved | Verify child exactly and native end-to-end file | source/real_PC |
| WatchPorn | `PUBLISHER_ORIGINAL_UNVERIFIED` | Catalog present; exact selected original not successfully device-verified | Exact selected download/manifest; whole media receipt | user_report/source |
| PornPics galleries | `ALBUM_DISCOVERY_OR_403` | User reports cosplay album media/thumbnail failures and site 403 | Real gallery CSS, selected album originals and published count | user_report |
| 4KHD cosplay/TeraBox | `PUBLISHER_ARCHIVE_UNRESOLVED` | Selected album archive unresolved; thumbnail must not substitute for original ZIP | Same selected TeraBox file and image count | user_report/source |
| CosplayTele | `MISSING_THUMBNAIL_OR_LISTING` | Visible cards but adapter reports no gallery thumbnail | Repair published listing selectors, then selected albums | user_report/source |
| Celeb.st / cosplay discover | `ALBUM_OR_ORIGINAL_UNRESOLVED` | User reports unreliable model/artist gallery selection and unrelated results | Exact performer selection, gallery ID matching, source count | user_report |
| Performer/model discovery | `WRONG_ITEM` | Unrelated content saved instead of selected model/performer | Use stable publisher creator and album identifiers; reject mismatches | user_report |
| FreeWebNovel — Dual Cultivation EPUB | `CHAPTER_WALK_OR_INCOMPLETE_EXPORT` | Real chapter-1 and chapter-1147 HTML accessible; app EPUB fails | Verify index/next reader, durable chunk continuation and completed EPUB integrity | user_report/real_PC |
| Torrent end-to-end | `METADATA_OR_PEER_STALL_PLUS_MISSING_EXPORT` | User says module never works; current code quarantines every finished torrent, final public export not implemented | Use real seeded open torrent and verify pieces then independent trusted verdict and authorized export | user_report/source |
| X Following accuracy | `FALSE_POSITIVE_SUBSCRIPTIONS` | Queue includes users owner does not follow; old migration deletes only bcgame | Authoritative own /following reconciliation preserving manually requested accounts | user_report/source |
| Parallel webpage downloading | `WORKER_ADMISSION_GATE` | Second process native-only when available RAM below 4GB; device 3.18GB; primary one website at a time | Two selected independent webpage downloads advancing simultaneously without OOM | device/source |
| Queued/paused lifecycle | `QUEUE_STARVATION_OR_INCORRECT_UI` | Queued rows persist with no active worker; pause/resume controls ambiguous; discard missing | Lease ownership and eligible-queue diagnostics; clear/dismiss without deleting originals | user_report/source |
| Application stability | `CRASH_OR_LOW_MEMORY` | Frequent phone crashes and historical low-memory exits; no attributable stack captured | Device crash stack/tombstone and stable repeat session | device |
| Three legacy interfaces unified into one | `UI_PARITY_MISSING` | Kuroha artist backfill works in legacy only; switching required | Feature parity proven before deleting old entry points | user_report |
| Pixiv restored normal provider flow | `REGRESSION` | User reports Pixiv stopped; no newly verified authenticated transfer | Real own-account on-device source and duplicate-free saved original | user_report |
| Provider thumbnail previews / video posters | `MISSING_ASSET` | Hentaibros/cosplay Rule34 posters missing despite valid first-party image endpoints in some cases | Preserve native aspect, referer-aware fetching and decode | user_report/real_PC |
| Pornhite / Pronhite redirect behavior | `REDIRECT_UNEXPECTED` | User reports unexpected cross-site redirect | Reject unapproved final host; verify canonical provider and selection | user_report |
| OMG provider duplication / acceptance feature | `UNWANTED_DUPLICATE_UI` | User requested OMG removal if duplicative of FreePornVideos; acceptance feature unwanted | Remove only confirmed duplicates/unwanted entry points, preserve data | user_report |

## Unverified; not silently counted reliable — 3 named scopes

| Module/site | Issue subtype | Evidence / observed behavior | Required acceptance | Evidence type |
|---|---|---|---|---|
| NotFans, Cums, XXXI, Camstreams and remaining registered video providers | `NO_SELECTED_END_TO_END_RECEIPT` | Registered/discoverable is not proof of full download | Test each exact selected page and whole playable file | source |
| OneJAV / XXXClub / Nyaa / torrent search index | `NO_SEEDED_REAL_SWARM_TEST` | Index page/metadata not sufficient for BitTorrent transfer | Seeded legal test, selected file, progress and release | source |
| Additional comic/novel/gallery providers not explicitly user-tested | `NO_AUTHORITATIVE_DEVICE_RESULT` | Cannot assume reliable from templates and HTTP status | Evidence by selected chapter/album and file integrity | source |

## Nine principal phone failure register
Exactly these nine are the named mandatory video failures: **Jav Guru, FullPorner, JAVIDEO, Pornhub, Roshy TV, 3DHentaiX, HentaiHaven, UncensoredHentai, MP4Hentai**. A native database snapshot has *nine failed jobs*, but the older diagnostic bridge showed eight coarse IDs; this does not prove a 1:1 mapping between jobs and providers. All nine remain `NOT_WORKING` for complete selected original transfer.

## Engineering priority and source references
1. `AUTH_OR_403` / `REGIONAL_NETWORK_GATE` / `VERIFICATION_GATE`: retrieve redacted selected HTTP/stage/network route; no bypass or fabricated access.
2. `PLAYER_UNRESOLVED` / `EXTRACTOR_UNSUPPORTED` / `EXTERNAL_HOSTER_NO_FILE`: site-specific article → active exact iframe/player → publisher original source, no recommendation/ad/HTML fallback. See `SiteSpecificOriginalSources.kt`, `FullPornerOriginalSource.kt`, `Mp4HentaiPublishedHost.kt`, `JavGuruOriginalSource.kt`.
3. `WRONG_MEDIA_RISK`: 3DHentai must use signed selected post_id + nonce. Hentaibros must use exactly one selected `#wpfp_*` configuration, not thumbnails/advertisements.
4. `TRANSFER_TIMEOUT`: actual FreePornVideos 480p 231,602,681-byte full read timed out at 390 sec; exact wheel metadata/range succeeds. Capture durable byte counts, stalls, reconnect/resume in native transfer.
5. `MISSING_EXPORT`: torrent currently quarantines even fully verified pieces in `TorrentTransfers.kt:413-445`; do not mark complete until an authorized malware verdict and actual selected-file export.
6. `WORKER_ADMISSION_GATE`: 4GB threshold in `VideoAgentAdmission.kt` restricts native-only second worker with 3.18GB available. Avoid blindly allowing two memory-heavy WebViews/Python engines and causing OOM.
7. `CHAPTER_WALK_OR_INCOMPLETE_EXPORT`: `NovelEpub.kt` and `NovelEpubService.kt` must follow exact publisher reader, persist every chapter and generate verified EPUB only when complete.
8. `FALSE_POSITIVE_SUBSCRIPTIONS`: reconcile X with authorized own follow list without deleting genuine user-added queues.

## Recorded actual prebuild evidence
See [PREBUILD_REAL_ENGINE_VERIFICATION_2026-11-16.md](PREBUILD_REAL_ENGINE_VERIFICATION_2026-11-16.md). Exact bundled `yt-dlp 2026.10.26` imported and used, selected public media byte-range checks and full-file timeout measured. All partial successes are distinguishable from final Android receipts. Source repair register: [DOWNLOADER_FAILURE_REGISTER_AND_REPAIR_2026-11-16.md](../production/DOWNLOADER_FAILURE_REGISTER_AND_REPAIR_2026-11-16.md).

## Source-change and release gates
Do not change/delete user downloads, databases, artist IDs, preexisting authorized connection, queues or signatures. Preserve Toonily/MangaRead ZIP, Kuroha legacy path until unified parity, Rule34 original transfer, Kiutaku real-image source, and Koikatsu native aspect. Test engine with real selected bytes before building; then only signed in-place app update and on-phone full-file/identity/crash checks. Repair-source changes are not silently promoted to device passes.
