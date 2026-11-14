# Asterion Downloader staging handoff · 14 November 2026

Resume on [`work/downloader-single-build-staging-20261112`](https://github.com/Azrazieliz/AsterionDownloader/tree/work/downloader-single-build-staging-20261112). The last tested code is [`3caf17d31396a0a5a892ae4ce8aac2c79a82b603`](https://github.com/Azrazieliz/AsterionDownloader/commit/3caf17d31396a0a5a892ae4ce8aac2c79a82b603). The [repair history](https://github.com/Azrazieliz/AsterionDownloader/compare/ae9bf7c1...3caf17d3) includes the selected-album, provider and transfer-engine changes. The code at that revision declares version 1.3.60, version code 89 and acceptance package `com.azrael.pixivdumpsync.acceptance`. The screenshots did not show the installed revision. Asterion Control confirmed that the Samsung was reachable and the acceptance package was installed. This revision has no new owner-signed release or Samsung acceptance receipt. The owner reserved approval for the next signed acceptance build.

## Code and test receipts

| Revision | Change |
| --- | --- |
| [`15950c3f`](https://github.com/Azrazieliz/AsterionDownloader/commit/15950c3f) and earlier staging commits | Route a selected album through its existing provider. Keep album identity in the durable queue. Separate verified album membership from unrelated historical rows while retaining the saved files. Require action for unresolved players and expired zero-byte attempts. Retire Archivebate from provider catalogs and scheduled work. |
| [`16edbd69`](https://github.com/Azrazieliz/AsterionDownloader/commit/16edbd69) | Scope CosplayTele WordPress galleries to the selected post ID, prefer a single original per responsive image, and allow a retry of a legacy completed album whose count disagrees with its title. Check the count and image signatures extracted from the 4KHD archive. Cover publisher discovery with the zero-byte watchdog. |
| [`563d0c96`](https://github.com/Azrazieliz/AsterionDownloader/commit/563d0c96) | Apply the same 4KHD original-archive path to site backfill. Preserve the selected player referrer for browser and transfer requests. Clean up WebViews on cancellation. |
| [`1f056a9d`](https://github.com/Azrazieliz/AsterionDownloader/commit/1f056a9d) | Read HentaiBros selected Flowplayer `data-item.sources`, encode literal spaces in media URLs and use a consistent mobile user agent. Keep ordinary playback activation scoped to the selected player. |
| [`6dbe8a91`](https://github.com/Azrazieliz/AsterionDownloader/commit/6dbe8a91) | Read Roshy’s selected Beeteam368 player configuration and its declared Kamehaus iframe. Add Chaturbate **Watch Live**, which opens the official site in the phone’s browser. |
| [`3caf17d3`](https://github.com/Azrazieliz/AsterionDownloader/commit/3caf17d3) | Check local video/audio tracks and playable duration before publishing to Downloads. Compare against expected runtime when available. Count saved image files in the gallery image view. Remove retry and resume controls for retired video providers while retaining saved-file access. |

- The [pilot and acceptance JVM suites](https://github.com/Azrazieliz/AsterionDownloader/actions/runs/43633768863) passed on `3caf17d3`. The tests include the synthetic 37-photo album plus 17 recommendations, selected WordPress media, and scoped 4KHD archive selection.
- The [Android transfer simulation](https://github.com/Azrazieliz/AsterionDownloader/actions/runs/43633768837) passed on `3caf17d3`. It exercised the native HTTP lane and bundled Python engine using a controlled local media response. Provider-specific Samsung transfers need separate receipts.
- An [earlier live integration run](https://github.com/Azrazieliz/AsterionDownloader/actions/runs/43447555853) produced a 14-chapter EPUB from live FreeWebNovel content and checked other provider parsers. That run used an earlier revision and a bounded chapter selection.

## Provider findings

| Provider | Evidence from this pass | Remaining acceptance or defect |
| --- | --- | --- |
| CosplayTele | Owner reported an album tap creating a generic Following card and a 37-photo title yielding 54 files. A live selected article exposed a WordPress gallery keyed to its post ID. The parser and regression cases are in staging. | Locate the exact owner-reported album on the phone. Retry it, confirm one provider and exactly 37 original photos, and check legacy receipt reconciliation. The live HTML specimen was a different post. |
| Kiutaku | Selected numeric gallery was reachable. An original JPEG returned HTTP 200 and all 281,036 bytes with a valid JPEG signature. The screenshot showed a followed gallery retry. | Verify the owner-reported gallery with its existing authorized session and save the full set to Android Downloads. |
| 4KHD | Verified an exact album detail route, publisher archive bridge and TeraBox landing page. The sample share yielded a token and share ID. Its public listing returned `errno -4` with an empty file list. | Resolve the official archive through an accessible publisher or authorized TeraBox path and save the exact advertised photo count. Webpage previews remain cover evidence. |
| Buondua, Celeb.st, Porn Pics | Buondua returned an HTTP 200 listing. Earlier source tests cover gallery extraction for supported routes. | Complete a selected album per site on Android and compare its count and source URLs with the publisher. |
| Archivebate | Repeated owner-reported catalog failure with zero cards. Removed from approved catalogs and future work. Existing local files and history remain accessible. | Confirm the source is absent and legacy rows offer no transfer action after upgrade. |
| HelloPorn | Owner reported the generic “No verified original found on this page” error. A selected first-party page exposed an exact VOE iframe. The VOE probe failed to return media from the test route. | Confirm the card reaches VideoFlow, then verify a complete original from the selected embed. |
| HQPornerxxx | Owner reported an indefinite first-byte wait and an extractor failure. A live selected player exposed the MyXStudio to MyDaddy iframe chain. The chain and referrers are handled in staging. | Verify the exact reported item, real bytes and final playback. A terminal timeout establishes queue recovery only. |
| HentaiBros | The actual selected Flowplayer configuration supplied an MP4 URL with spaces. After encoding it, the response was HTTP 206 with an MP4 signature, 256 bytes read and 234,913,756 bytes advertised. | Save and play the complete file on Samsung. |
| FullPorner | A real browser exposed three quality variants in the selected first-party player. All three range probes returned HTTP 206 with valid MP4 headers. | Confirm one complete Android save and playback. |
| Rule34Video | A current selected item exposed three original formats. A complete HTTP 200 response contained 3,425,759 bytes with a valid MP4 header. A different historical item had returned 404. | Confirm Android publishes the current item and reports any genuinely unavailable item accurately. |
| Roshy TV | The selected post’s Beeteam368 configuration pointed to a Kamehaus iframe. The iframe returned HTTP 200 with player script and an HLS marker. Staging now follows the declared embed. | Verify a complete playable stream. Distinguish file-hoster landing pages from playable media. |
| Jav Guru | Public listing reachable. Screen captures show selected jobs failing in the extractor. The selected-page probe yielded no verified original. | Reproduce the exact queued items and verify the selected player’s source and full save. |
| JAVIDEO | Public page reachable. The desktop browser showed an MP4 source element from a CDN. | Verify selected-item provenance, complete file and playback on Samsung. |
| Pornhub | Public listing reachable from the Windows worker. The phone showed a selected job retrying. | Reproduce the exact transfer on the phone and distinguish site access from CDN failure. |
| MP4Hentai | Selected detail exposed named Mirror and Upfiles links. Probes reached HTML landing pages, including the Upfiles redirect. | Obtain an authorized complete file from the specific selected post. |
| 3DHentaiX | The rendered page exposed a video element. Earlier inspection saw short previews and a later page exposed a signed-playback configuration. | Verify a complete authorized session and file for the selected item. |
| Uncensored Hentai | The exact selected NHPlayer iframe was reachable. The inspected response produced no verified original. | Verify the selected player yields a complete file or an accurate access error. |
| Hentai Haven | Public HTML was reachable; a separate browser probe encountered site verification and exposed no playable original. | Test the selected item through the supported site session. |
| OnlyHentai | The owner reported this as the working exception. It retained its existing provider adapter. | Recheck a real full save as part of regression acceptance. |
| Chaturbate | Added a Watch Live entry for the official player. [Platform terms](https://chaturbate.com/terms/) govern saving content. [Private show recordings](https://support.chaturbate.com/hc/en-us/articles/360035585132-What-are-private-show-recordings) are available when the broadcaster enables them. | Verify the entry opens the provider on Samsung. |

## Other file types and sites

| File or module | Evidence | Remaining acceptance or defect |
| --- | --- | --- |
| FreeWebNovel EPUB | The reported title and chapters 1, 2 and 14 returned HTTP 200 with reader content. Earlier integration assembled a 14-chapter EPUB. | Complete the entire owner-selected book on Android, verify chapter order/count and confirm resume with retained cache. |
| NovelBuddy and ReadNovelFull EPUB | Tested NovelBuddy title returned 404. ReadNovelFull homepage returned 200. | Use a current published title for each, then verify a complete EPUB and resume. |
| MangaRead, Toonily and Sushi-Scan ZIP | MangaRead returned a complete 81,373-byte reader JPEG. Toonily listing returned 200. Sushi-Scan exposed 55 chapter links and a reader container, but no verified image was retrieved in the probe. | Finish and open a chapter-order ZIP per site. |
| NHentai and mirror | Mirror listing returned 200. Primary request failed on the probe route. | Verify exact gallery count and a complete ZIP on the phone. |
| Torrents | OneJAV returned 403. Sukebei and XXXClub listings returned 200. A Linux distribution `.torrent` returned all 484,518 bytes and valid bencoded metadata. XXXClub is in **Torrents → Adult Video**. | Transfer and verify a permitted torrent payload from peers, including pause/resume. Recheck OneJAV using its authorized session. |
| Koikatsu assets | BepisDB returned a small HTTP 200 HTML shell. Earlier user feedback requires full-proportion card thumbnails. | Verify the original PNG with its embedded card data and check that the app displays the image uncropped. |
| Pixiv and cloud | Pixiv’s public page and MP4Directs were reachable. Existing [Kuroha history bridge](KUROHA_PIXIV_HISTORY_BRIDGE_2026-11-11.md) covers prior Pixiv downloads and failures. | Test the actual device account, history import and duplicate suppression. Follow a verified local save through Rounder and TeraBox and reconcile the cloud receipt. |

## Resume in this order

1. Use the staging branch and retained owner signing identity when the owner approves the next acceptance build. Verify the exact source commit, package, signer and installed version through the existing Asterion Control route.
2. Test the selected CosplayTele gallery, exact 4KHD archive and the reported video queue. For each provider, record the selected item, bytes received, complete file, Android receipt and playback. Start with HentaiBros, FullPorner and Rule34Video because the media response was already observed.
3. Run two independent transfers together. Verify both byte counts advance, then pause, retry and cancel individual jobs. Include service restart and sufficient free space for the album archive and extraction. Reproduce any crash and capture the stack trace before changing lifecycle code.
4. Verify the rest of the file matrix after the engine pass. Keep publisher timeouts, access failures, extractor errors and invalid final files distinct in the handoff.
5. For Nexus, prioritize a shared resumable queue, exact-item failure receipts and cross-storage duplicate reconciliation. Do not reintroduce unapproved provider lists.
