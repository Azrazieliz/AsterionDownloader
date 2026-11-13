# Asterion Downloader — end-to-end reliability reaudit (2026-11-13)

## Scope and acceptance

Canonical repo: Azrazieliz/AsterionDownloader. All changes in `work/downloader-single-build-staging-20261112`, preserving existing installed package, approved UI, data, and source histories. The source CI workflow only executes `testPilotUnitTest testAcceptanceUnitTest`; it DOES NOT produce a signed APK or prove Android acceptance.

User-provided Android screenshot: "Asterion Downloader Acceptance has stopped." The immediate exception and its stack trace are not available here. Root causes of *every historical APK crash* therefore cannot be definitively assigned from source history alone. The following are source-proven failure classes, not an invented mapping from each past version to a crash.

## Reproducible source defects corrected in staging

| Stage | Evidence from earlier source | Implemented correction | Acceptance gate |
| --- | --- | --- | --- |
| Old video part-file restoration | VideoFlow used `cacheDir` whenever legacy bytes existed, so Android could evict work still in progress | `VideoStaging.prepare` copies old partial files into durable app-files staging with write/sync/size check and retains old source | Reboot + pause/resume a multi-GB file on device |
| Video cancellation | UI process's `VideoEngine.stopRequests` and `extractionJobs` were process-local, while two services run in `:videoflow_primary` and `:videoflow_secondary` | Private cancellation receivers in each service process; SQLite lease invalidation and native extractor callback cancellation checks | Verify Cancel actually interrupts both active downloads, including yt-dlp Python native stage |
| Legacy yt-dlp downloader | Java wrapper drops some output and Referer options, yielding read-only working-directory EROFS and CDN-fragment 403 | Existing persistent working-directory and supported `--add-header` repair retained | Real HLS and direct MP4 transfers to Saved |
| Python extractor startup | Eager Python initialization previously made both transfer services fail before directly accessible HTTP originals could run | Existing lazy Python initialization and direct HTTP media lane retained | Repeated background service restart on actual Android |
| Repeated catalog scanning and redraws | Each site refreshed view trees independently; high thumbnail memory and large source grids could cause instability | Existing coalescing/thumbnail subsampling retained; prevent bogus Kiutaku thumbnails | Memory/ANR soak test on phone |
| EPUB FreeWebNovel | Reader chapter numeric IDs differ from displayed numbers; some reader templates omit heading; prior chapters cached incomplete JSON | Use exact published index record as fallback; detect chapter-count shortfall; replace corrupt cached JSON; validate EPUB ZIP before publishing | Test each provider's real long book, including append and resume |
| EPUB other sites | Generic provider index reader can terminate before all advertised chapters | Enforce `chapterCountHint` lower bound and refuse false-complete EPUB | Live NovelBuddy/ReadNovelFull long-book tests |
| OneJAV | Genuine torrent index was also sent through generic adult gallery parser; native torrent fetch discarded authorized site cookies/proxy | Native per-site check, authorized cookies/proxy and legitimately connected browser-session fallback; separate from VideoFlow | Actual listing/torrent transfer if server grants access |
| Kiutaku | Site homepage covers can be multi-image montages; no proof that an image is an individual original | Verify numeric gallery detail pages, use actual original-photo image URLs as preview; invalidate old provider-only montage cache | Inspect real album originals, verify image count/quality on device |
| Performer/model recognition | Profile verifier only accepted Pornhub and WatchPorn; name suggestion only matched prefixes; `NameCrawler` silently truncated after 120/240/300 hits; DB persisted only 300, UI showed 24/100 | Add documented FullPorner profile; substring search; persist all eligible source-attributed results; 24-per-page navigation; preserve manual review when identity unverified | Multi-provider live exact-person search, paging and attribution on device |

## Four historically blocked adult providers — truth table

| Provider | Correct transport route | Current evidence | Status |
| --- | --- | --- | --- |
| OneJAV | Native torrent metadata, not streaming MP4 | Prior independent client HTTP 403; source previously dropped session credentials. This was repaired, but HTTP 403 may persist on a genuinely blocked client | Source routing corrected; live access unverified |
| Jav Guru | First-party video article HTML + embedded player | Android-specific HTTP 403; SourceIndex already has same-site cookie + authorized WebView DOM fallback. No proof site provides accessible originals to the app | Blocked/unverified, not fixed |
| CUMS | First-party public video page | HTTP 403 from reported Android tests; no verified public response to parse | Blocked/unverified, not fixed |
| Archivebate | Public video entries, only if served | Public homepage returns access/age-gate shell without genuine video cards. Reject /2257 legal notice and category links | No accessible cards; adapter cannot invent originals |

**Never** fabricate media links, bypass provider restrictions, or count homepage covers as downloaded originals.

## End-to-end release conditions

1. APK starts repeatedly without crash: launch, navigating Home/Cosplay/Adult/Books/Downloads, long scrolling and background/foreground.
2. Two distinct downloads progress concurrently. A paused/cleared job stops in its OWN service process, and retry recovers without duplicate or corrupted bytes.
3. Videos and EPUBs become Saved only after bytes, format/integrity, and durable MediaStore receipt are verified. Android reboot preserves partials, queued and done records.
4. Check real source listing, actual detail-page original, bytes received, completion/playback or book page count, and recovery on every provider. Distinguish genuine 403/access gate from parser bugs.
5. Cosplay source tiles are not galleries until real individual photos are resolved; model results remain source-attributed and accessible across pages.
6. Install a signed acceptance update with existing Windows signing identity, **not a CI-random signer**. On-phone verification is required before marking release complete.

**Outstanding**: Android crash stack and actual device tests; live OneJAV/Jav Guru/CUMS/Archivebate access; full EPUB provider matrix; long video cancel and Saved playback; Android signed APK. Source CI success alone does not discharge these gates.
