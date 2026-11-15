# Asterion Downloader — adult video failure matrix
Updated: 2026-11-15. Persistent, non-secret source of truth for porn/hentai repairs.

## Acceptance status
**FAIL: All porn and hentai providers by default.** Per user's device testing, source discovery, poster visibility, HTTP 200, a valid extractor response, or one previously successful transfer is **not** proof of a working download. Do not close any row until its exact user-selected original has completed end-to-end on Samsung, is the correct video (not an ad/preview), can be opened, and survives app restart. Do not restart the old cycle of signed APKs for source-only tests.

| Provider/engine | Device/user observation | Source-level finding / current action | Acceptance |
|---|---|---|---|
| Archivebate | 24 indexed cards and posters once appeared after several minutes; next run source discovery regressed. Selected video 16458050 failed with **Python extractor** error. | Exact /watch/ recording may publish Mixdrop /e/; original is obtained from its own selected HTML5 player. Generic yt-dlp on the watch page/embed is not a verified original. Native WebView stage in `ArchivebateSelectedMedia.resolve` must be observed on device. | **FAIL**, no verified complete original |
| HelloPorn | Selected articles do not resolve original files. | Selected `/video-...` publishes one `voe.sx/e/...` iframe; no directly verified MP4 appears on publisher HTML. Exact selected iframe must lead to a playable original and actual file bytes; no unrelated previews. | **FAIL** |
| FreePornVideos | Previously working provider regressed. | Selected article `/videos/93823084/...` directly publishes `/get_file/.../93823084_2160m.mp4/` and lower-quality variants. Prior transfer ignored those sources and asked Python to rediscover an original. Change introduced: `FreePornVideosOriginalSource.selectedOriginal` uses the original selected ID and best published MP4 before extractor fallback. | **FIX IN SOURCE**, **DEVICE FAIL / UNVERIFIED** |
| Hentaibros | Previously sporadic success; user requests fail-by-default until reliable. | WordPress post publishes `#wpfp_<id>[data-item]` selected source; wrong CSS selector previously dropped it. | **FAIL**, verify complete selected output |
| OnlyHentai | Previously sporadic success; user requests fail-by-default until reliable. | Provider-specific selected player still needs full transfer receipts. | **FAIL** |
| HentaiHaven | No complete transferred original confirmed. | Static page HTTP 200, no selected MP4 in first HTML; cannot use poster URLs. | **FAIL** |
| 3DHentaiX | Wrong media/ad could be saved even when playable. | Selected `post_id` + nonce publisher AJAX required; generic HTML5 source is not proof of selected content. | **FAIL** |
| MP4Hentai | No complete original confirmed. | Selected article publishes external Mirror/Upfiles links; bracketed publisher filenames required URI encoding. Hoster HTML is not MP4. | **FAIL** |
| Rule34Video | First-party selected quality URLs observable in page. | HTTP 206 + video/mp4 probe not sufficient; verify final correct full media. | **FAIL** |
| JAVIDEO, JavGuru, HQPorner, FullPorner, Eporner, Roshy, WatchPorn, Pornhub, UncensoredHentai, others | Unreliable or failed per device; do not infer success from first-page discovery. | Authoritative exact selected iframe, download button, HLS manifest or source-specific API required; no general ad fallback. | **FAIL** |

## Reproduction / evidence ledger
- Device screenshots: Archivebate `24 indexed media cards · 24 poster URLs · Source error`, then `16458050 Failed · Python extractor: Video extractor cannot...`. This is *discovery succeeding but download failing*.
- Public selected-page probes (owner PC): `scripts/verify_selected_video_originals.py`. This returns HTML/iframe proof, **not** complete downloads.
- Exact selected-byte probe: `scripts/verify_selected_source_media_bytes.py`. At most a small HTTP Range read; **not** complete downloads.
- Android retained diagnostic sources: `VideoDiagnosticsReport.create`, `VideoTransferJournal`, Asterion Control read-only diagnostic bridge. Do not copy cookies, tokens, signed file paths or private pages into this repository.
- Control diagnostics need explicit two-sided authorization; earlier bridge reported `approved:false`. A 503 or missing approval is not a successful device trace.

## Failure taxonomy (do not silently reclassify)
1. **DISCOVERY_FAILED:** selected page/article not indexed or wrong provider.
2. **PLAYER_UNRESOLVED:** article exists, selected video player/iframe not linked to a concrete file/manifest.
3. **AUTH_OR_403:** server denies the user-authorized browser/download session. Preserve provider domain and HTTP code only.
4. **EXTRACTOR_UNSUPPORTED:** Python cannot resolve the selected hoster; no bytes are attempted. Do **not** retry the same page through multiple identical extractors.
5. **NO_ORIGINAL_BYTES:** link resolved but transfer yields zero real bytes / stalls.
6. **WRONG_MEDIA:** ad, teaser, sidebar item or different episode; playable does **not** mean correct.
7. **INVALID_CONTAINER:** downloaded file cannot be validated and opened.
8. **RECEIPT_VERIFIED:** only when selected media, full bytes, destination receipt and openability verified end-to-end.

## Release gate
A provider is `PASS` only after the *same exact selected video* completes on the user's phone. Record: provider, selected article ID (non-private), hoster category, source stage, HTTP error category, receipt size, playable yes/no, content identity yes/no, app version. Keep this ledger in repo between builds and fix one verified failure cause at a time. Never label a static page HTTP 200, cards or parser unit tests as media-download success.

## Architecture rule
One unified Asterion UI; retain the original Pixiv / Kyora / VideoFlow engines and databases. Preserve originals and queues. All video failures must have a stable source stage and be distinguishable from network denial. Do not send another APK solely because CI is green.
