# Asterion Download — ten-provider repair checkpoint — 2026-11-15

## Owner's actual on-device acceptance result — 2026-11-15

**Supersedes source-only success interpretations.** The owner directly tested the newly delivered owner-signed acceptance build and reported:

- **Rule34Video:** works (the only reported video-provider success). Preserve this exact selected-file route and native transfer.
- **JAVIDEO:** still fails to download and some *first listing thumbnails no longer display*.
- **Every other reported provider:** still fails in the owner's actual on-device workflow (Jav Guru, Archivebate, HelloPorn, Pornhub, Roshy JAV, 3DHentaiX, HentaiHaven, MP4Hentai).
- **Random crashes:** still occur. No proven root cause or crash-free on-device run exists.
- The owner's direct acceptance result overrides successful source unit tests, CI HTTP probes and controlled emulator tests.

**Current acceptance verdict: FAILED — not releasable or finished.** Do not build/publish another "all fixed" APK from speculative extractor edits. Continue with real selected-page/device failure receipts, recorded file bytes and a confirmed device crash trace.

**Confirmed listing regression cause in code:** VideoSearchPreviews queued up to 120 first-page cards into a 72-slot DiscardOldestPolicy executor. Under load, early thumbnail requests were silently discarded. The fix staged in `0634b862` preserves first-visible queued image tasks, expands capacity and merges later real cover links for duplicate video URLs; this remains **source-only until device verified**.


**Branch:** `work/downloader-single-build-staging-20261112`  
**Policy:** the owner's single remaining **signed acceptance APK** is RESERVED. Do not publish a purported repaired app until all relevant on-device acceptance requirements actually pass. A passing JVM suite, Linux probe or Android emulator with controlled media is not a live-provider receipt.

## Scope: observed defects and source changes

| Provider | Source strategy now staged | Verified / still required |
|---|---|---|
| Jav Guru | Publisher shortcode → selected STREAM → signed HLS and fragment transfer; generic ad iframes rejected | Exact selected media transfer and complete Android playback **not verified** |
| JAVIDEO | Activate selected Play; only the selected Streamtape e/v/embed iframe is permitted; inspect that iframe's own HTML5 currentSrc before transfer; reject parent autoplay ad | Real Streamtape media still **not verified** on phone |
| Archivebate | Connected/public listing and selected /watch id resolved separately; selected Mixdrop → mxdrop.click path and selected CDN source | Complete Android selected recording **not verified** |
| HelloPorn | Select exact one VOE /e/id iframe from owner page, not ads; resolve player media from static or rendered DOM; no raw iframe-webpage success | VOE publicly refused connection in previous metadata check; complete Android transfer **not verified** |
| Pornhub | Official extractor on actual accessible selected item, distinguish region/access from transfer errors | France/geographic restrictions and permitted exit require real evidence; **not verified** |
| Roshy JAV | Selected Beeteam368 Original Stream configuration and exact Kamehaus /e/id; player activation added; preserve HLS handoff | Complete Kamehaus HLS/MP4 **not verified** |
| 3DHentaiX | Only the unique selected article player; activate selected player; refuse unrelated 30-sec video suggestions and ambiguous media | Selected full source identity, access and final transfer **not verified** |
| HentaiHaven | Recognize actual episode path; report HTTP 200 verification shells as access problems; do not accept an unverified article as media | Selected episode may still be behind verification; complete transfer **not verified** |
| MP4Hentai | Exact WordPress post → published Mirror/Upfiles → bounded hosting links; wait for Mirrored's async hosting links; reject HTML and unrelated anchors | Public selected post accessible and file-host landing pages observed; downstream **direct** media and full file **not verified** |
| Rule34Video | Require exact selected first-party quality-labelled `/get_file/?download=true`; dynamically open the selected Download control; exclude unrelated card links; pass direct MP4 into native MIME/range/signature validator | A previous selected file returned a valid MP4 byte response on Windows; current CI public requests return HTTP 503; complete Android save **not verified** |

All ten listed sources remain subject to the owner's real-media acceptance requirements. No provider is marked "repaired and verified" merely because its parser returned a URL.

## Source changes completed in this continuation

- [3e076b4](https://github.com/Azrazieliz/AsterionDownloader/commit/3e076b419c10a4a7235b59780cdb18f57a7d5992): dedicated MP4Hentai published host chain, first Rule34 dynamic formats, HentaiHaven verification, tests.
- [6aa25f5](https://github.com/Azrazieliz/AsterionDownloader/commit/6aa25f57320f295e7ed7229a386ae32f9dc9cea9): fix Kotlin compile-time selected page initialization.
- [71f1df2](https://github.com/Azrazieliz/AsterionDownloader/commit/71f1df2000e988761dd38839ae3397ceb0a6f472): public live probe of exact selected providers and hoster URLs.
- [8a70be6](https://github.com/Azrazieliz/AsterionDownloader/commit/8a70be666ced353600c897d423871d5ab262f261): selected player activation and JS host links.
- [e9b74cd](https://github.com/Azrazieliz/AsterionDownloader/commit/e9b74cd9f84db74912d3a993d0cc11a3bf35bbaf): error classification tests and bounded failure handling.
- [b588707](https://github.com/Azrazieliz/AsterionDownloader/commit/b588707add3d28c9f41a7d89af92b4a837a99f18): selected Roshy player regression fixture.
- [da4bd94](https://github.com/Azrazieliz/AsterionDownloader/commit/da4bd946d56bb38a68d5b8352fe7e03cf425c859): strict Rule34 selected first-party original; no unrelated HTML fallback.
- [e3b3cfd](https://github.com/Azrazieliz/AsterionDownloader/commit/e3b3cfd874a43a3e291c3307559ca693b12b365e): exact JAVIDEO Streamtape / HelloPorn VOE player-media handoff.
- [4a9c84f](https://github.com/Azrazieliz/AsterionDownloader/commit/4a9c84fa8e7e79dba86e874f9277d71022e001d1): Streamtape route improvements; Android emulator assembly now **manual only** rather than automatically building on staging pushes.
- [568c044](https://github.com/Azrazieliz/AsterionDownloader/commit/568c0447a4ee24662ad3410a002f87495ac12e53): Mirrored.to host-link readiness wait.
- [ed42f06](https://github.com/Azrazieliz/AsterionDownloader/commit/ed42f064b076b12c003dcc319725e564623fa7af): selected Rule34 related-card exclusion and early rejection of unsupported article/hoster fallbacks.

## Verified regression evidence

- Source-only Pilot and Acceptance suites on **ed42f06** passed: [run 43720884460](https://github.com/Azrazieliz/AsterionDownloader/actions/runs/43720884460). Earlier source-only suite on e3b3cfd passed: [run 43720111383](https://github.com/Azrazieliz/AsterionDownloader/actions/runs/43720111383).
- Android simulator against controlled local media passed on **da4bd94**: [run 43719870271](https://github.com/Azrazieliz/AsterionDownloader/actions/runs/43719870271). It reported `NATIVE_OK 256013`, `PYTHON_RUNTIME_OK version=2026.10.26`, `EMBEDDED_OK 256013`, `PASS`; no fatal crash in that run.
- Android simulator against controlled local media also passed on **e3b3cfd**: [run 43720111366](https://github.com/Azrazieliz/AsterionDownloader/actions/runs/43720111366), same named checks and bytes.
- Live public HTTP probe succeeded as a diagnostic job: [run 43717846536](https://github.com/Azrazieliz/AsterionDownloader/actions/runs/43717846536). MP4Hentai article and its hosters returned HTML; Rule34Video returned HTTP 503 from this CI exit; this DOES NOT establish selected original downloadability.

These checks deliberately do not claim that arbitrary publisher videos work or that the owner's observed random crash is fixed.

## Remaining blockers outside fixture coverage

1. **Selected publisher sources:** each of ten failing sites requires a live authorized selected-item trace through actual media resolution, complete bytes, verified playable file and MediaStore receipt on Samsung.
2. **Crash:** capture actual Samsung logcat exception/tombstone and first/final memory/queue pressure, then fix the proven cause and reproduce a non-crashing run. Bounding image queues alone is not proof.
3. **True parallelism:** VideoDb's secondary `nativeOnly` lease check admits concrete direct media but excludes selected webpage URLs. Primary `drainQueued` still serializes webpage jobs. The process-global CWD workaround in VideoEngine must not be naively parallelized because concurrent yt-dlp tasks could write into each other's staging folders. Rework with process-isolated or truly path-safe transfers and verify simultaneously advancing *bytes*, not just jobs.
4. **Preserve known successes:** FreePornVideos and WatchPorn user-observed working items, Kuroha/Pixiv deduplication, full-size Koikatsu cards, cosplay/Hentaibros and EPUB routing require regression coverage before acceptance. Provider retirement/removal decisions (Pornhits, conditional OMG) must remain intact.
5. **Signed acceptance:** only after real provider and regression evidence, build with pinned yt-dlp wheel SHA-256, retain installed package/signature, validate upgrade and the final stored original on Samsung. Do not treat an internal CI/emulator APK as the final user-release build.

**Decision:** source repaired and source tests green; real-provider/device acceptance incomplete. **Do not consume reserved signed acceptance build.**
