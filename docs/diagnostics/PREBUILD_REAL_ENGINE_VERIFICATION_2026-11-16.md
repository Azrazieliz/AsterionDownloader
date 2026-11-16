# Asterion Downloader — real-engine prebuild verification (2026-11-16)

**Status:** PREBUILD TESTING; *not* a passing Android app release. This record is evidence, not a claim that all providers work.

- Source: `Azrazieliz/AsterionDownloader`, repair branch `repair/downloads-nine-providers-backfill-diagnostics-20261116`, source revision `2652300bd05a088bc38d16d640b8456ab804b1c7`.
- Environment: existing Asterion Control Windows worker; Python 3.14; actual APK-bundled `yt_dlp-2026.10.26-py3-none-any.whl` imported and verified version `2026.10.26`; installed Microsoft Edge, FFmpeg/FFprobe.
- Execution: repository's existing source scripts and exact bundled yt-dlp wheel against real public publisher responses and media bytes. No new APK was built, installed, signed, or delivered. None of the original app source/DB/queues/files was altered.
- Evidence directory on owner PC: `D:\AsterionControlRelease\diagnostics\downloader_prebuild_20261116\`. Reports include `real-provider-results.json`, `site-extractor-metadata.json`, `edge_players.out`, `fullporner_range.out`, `selected_range.out`, `rule34_original_range.out`, `player_contracts.out`, and `roshy_live.out`. No login cookies or short-lived full media URLs recorded here.

## Precise verification language
- **WHOLE_FILE_BYTES_VERIFIED** = a complete real image HTTP response with matching declared length and image signature, obtained by an existing probe; does not prove the Android gallery queue/MediaStore integration.
- **ORIGINAL_MEDIA_RANGE_VERIFIED** = exact publisher-published media URL responded to a bounded Range request with media MIME, valid MP4 `ftyp`, and reported total length; not a complete file, playable-duration, or correct-video receipt.
- **EXTRACTOR_METADATA_VERIFIED** = exact bundled yt-dlp returned real formats; does not prove full download.
- **WEBPAGE_ONLY / GATED / UNSUPPORTED / TIMEOUT** = not working for full original transfer.

## Confirmed positive routes
| Site and stage | Actual measured result | Classification |
| --- | --- | --- |
| Kiutaku selected photo via mitaku.net | HTTP 200 JPEG; complete 281,036 bytes, declared 281,036, `truncated=false`, valid JPEG signature; evidence SHA256 prefix `a214d5d5cf9de6f60aa57c72` | WHOLE_FILE_BYTES_VERIFIED (image only) |
| Rule34Video selected first-party thumbnail | HTTP 206 JPEG; `Content-Range: bytes 0-4139/4140`, complete 4,140 bytes, valid image signature; evidence SHA prefix `03dbcbb9e48da889a88dd720` | WHOLE_FILE_BYTES_VERIFIED (thumbnail only) |
| FullPorner exact selected `/player/9509933/14` | Edge rendered three `<source type=video/mp4>` entries; each returned HTTP 206 `video/mp4`, 256 actual bytes with valid `ftyp`. Total advertised lengths: 400,115,983; 688,966,977; 1,231,039,363 bytes | ORIGINAL_MEDIA_RANGE_VERIFIED (no full saved file) |
| FreePornVideos selected `/videos/93823084/...` | Actual bundled yt-dlp extractor `HTML5MediaEmbed` resolved **3 formats**; selected first-party MP4 Range returned HTTP 206, 32,768 bytes and valid `ftyp` | EXTRACTOR_METADATA_VERIFIED + ORIGINAL_MEDIA_RANGE_VERIFIED |
| Rule34Video selected first-party quality URLs | Watch page HTTP 200; three explicit `/get_file/` qualities; all HTTP 206 `video/mp4`, 256-byte valid MP4 `ftyp` responses. Reported lengths: 720p 3,425,759; 480p 1,796,940; 360p 1,221,424 bytes | ORIGINAL_MEDIA_RANGE_VERIFIED; duration/content identity unverified |
| FreeWebNovel book chapter retrieval | Real chapters `/chapter-1` and `/chapter-1147` HTTP 200, 66,156 and 65,416 HTML bytes; actual reader container and numbered chapter headings detected | WEBPAGE/CHAPTER_HTML_VERIFIED only; no complete EPUB |

## Confirmed failures and insufficient evidence
| Provider | Measured failure or limitation | Required next valid proof |
| --- | --- | --- |
| FullPorner watch URL | Actual bundled yt-dlp: `no_supported_extractor` despite selected player's genuine video files | Use exact selected iframe → source parser/native complete transfer; check media length, duration, correct ID |
| Eporner selected URL | `extractor_player_signature_changed` | Update parser for current first-party player; verify selected original and bytes |
| Hentaibros selected URL | yt-dlp `no_supported_extractor`; Edge rendered multiple independent media: 47,579; 1,003,229; 455,267; 3,512,187 bytes observed by HTTP 206/MP4 header | Verify exact `#wpfp_*` `data-item` selected full original; never accept a preview/sidebar by MIME alone |
| 3DHentaiX selected URL | yt-dlp `no_supported_extractor`; visible 6,070,587-byte MP4 has valid header but **does not attest the requested work** | Use post_id + nonce signed WordPress API, then full selected first-party CDN transfer |
| HentaiHaven selected URL | yt-dlp `no_supported_extractor`; Edge HTML had verification gate, no selected `<video>` source | Authorized actual publisher browser/player access, not a site-verification shell |
| UncensoredHentai selected URL | yt-dlp `no_supported_extractor` | Exact nhplayer iframe and attributable full original |
| JAVIDEO sampled selected URL | yt-dlp metadata `metadata_probe_timeout_35s`; Edge's initial video/source is not proof of selected Streamtape file | Inspect active selected iframe and obtain actual correct original; no ad substitution |
| Rule34Video selected ID 4727221 | yt-dlp `publisher_not_found`; another Rule34Video selection has working publisher quality endpoints | Do not treat an unavailable ID as a general provider failure or verified full download |
| Archivebate public homepage | HTTP 200 returned age/Continue gateway; **zero** valid `/watch/` links exposed | User-authorized Continue/browser session, exact selected Mixdrop player and full file |
| MP4Hentai selected public page | HTTP 200, no static video source; external published hosting route is separate | Resolve genuine published hoster to downloadable file, not HTML landing page |
| Pornhub public homepage | HTTP 200 with site gating; no selected downloadable original tested | Accessible authorized network route and selected original proof |
| Roshy TV | First-party Beeteam368 `Original Stream` player and mirror controls observed; no verified complete media | Resolve attributable site player or approved file-host link, then full local media |
| HelloPorn/VOE | Public catalog has player elements but known VOE selected embed was not established as a verified original | Exact selected VOE iframe and successful media resolution, not generic unsupported URL |
| HQPorner | Embedded player chain requires selected iframe/host identity; end-to-end original not yet verified | Selected myxstudio → actual player → complete original |
| OnlyHentai, WatchPorn, other adult video sites | Homepage/card responses alone; no complete selected original receipt | Specific selected video, native verified bytes, duration/identity |
| Pixiv, Kuroha, X following | No account-authenticated real original or following-list data tested in this **prebuild public** suite | Preserve existing session/data; separate authenticated session acceptance |
| Torrents | No seeded swarm/file release test in this suite; source deliberately quarantines completed pieces pending scanner/release | Metadata + selected piece verification + authorized release/export |
| Other cosplay and comic providers | Not included in a whole-file end-to-end pass here | Selected album/series count, real originals, no wrong cards or truncation |

## Critical findings
1. Exact bundled yt-dlp is loadable and some formats resolve; this is **not** proof that the app's Android dispatch, cookies, worker lifecycle, or MediaStore publication is working.
2. Media download success must not be inferred from a 200 page, a 206 byte-range, `ftyp`, an HTML5 video, or an extractor format list. Selected-work identity, full bytes, playable duration and final destination receipt are separate gates.
3. Generic yt-dlp webpage extraction categorically fails on the tested FullPorner, HentaiBros, 3DHentaiX, HentaiHaven and UncensoredHentai selections. Site-specific selected player resolvers are required.
4. Media-looking HentaiBros and 3DHentaiX DOM elements include small unrelated/ambiguous responses; accepting the first playable MP4 reproduces the wrong-video defect.
5. Real book chapter HTTP and image file bytes work at the PC public-site layer, but this is **not** validation of Android EPUB or gallery queues.

## Completed full-file test: FreePornVideos still fails

The original `scripts/real_freepornvideos_acceptance.py` was run with the exact bundled wheel, local FFprobe, a 1.2-GB minimum free-space guard and temporary staging. The engine selected a **480p** first-party original and received **HTTP 200** with declared **Content-Length 231,602,681 bytes**. However the bounded full transfer **exceeded the 390-second deadline**. The script classified the outcome `blocked` and deleted temporary staging (`media_retained=false`). It did **not** emit `received_bytes` before timing out, so no verified byte count, complete file, or duration can be asserted. The subprocess exited 0 because its test script serializes blocked outcomes as JSON; this is **NOT a passing transfer**.

**Interpretation:** this case passes extractor discovery and 32-KiB media-range verification but **fails complete-file transfer** under the selected PC network conditions. Exact bottleneck (remote throughput, stalled read, route, or downloader logic) is not isolated by this script's final receipt. Do not substitute guessed speed, pretend it finished, or infer the Android MediaStore lane passes.

**Release gate still OPEN:** no provider here has an Android-installed-app verified complete MediaStore receipt and correct selected playable video. No APK was built or released in this test series.