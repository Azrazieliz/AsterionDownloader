# Asterion Downloader — observed faults in Android recording
Date: 2026-11-10. Source: user-supplied **1000103720.mp4**, duration 128.53 seconds, 1080 × 2340. This is a video/UI audit, **not** an on-device repair or transfer-log diagnosis.

## Transfer and queue blockers (P0)
| ID | Time | Observed evidence | Diagnosis confidence |
|---|---|---|---|
| D01 | 00:10–00:13 | One video reported as Transferring, yet shown as `Preparing / connecting`, `Inspecting / preparing original`, without byte/speed progress | Directly visible. Actual network stage/timeout unknown |
| D02 | 01:55–01:57 | A *different* video again marked Transferring but still `Preparing / connecting`; no real transfer metrics | Directly visible. Do not claim the same file was stuck for the full recording |
| D03 | 00:10 | One item `Retry scheduled` because `Video extractor returned no metadata...`; manual `RETRY` button still shown | Directly visible; extractor-specific failure |
| D04 | 00:10 → 01:56 | Counts change from **1 transferring, 20 queued, 2 paused, 1 need action, 0 failed, 0 saved (recent)** to **1 transferring, 19 queued, 2 paused, 2 need action, 0 failed, 0 saved (recent)** | Directly visible. More unresolved work, no newly reported saved originals |
| D05 | 00:10–00:17 | Multiple queued videos explicitly say `Waiting for transfer worker`. Only one active slot is visible | Directly visible. Whether worker pool is configured for one job or blocked by another requires logs |
| D06 | 00:10,01:56 | `Transferring` category includes preprocessing, not confirmed byte transfer; zero failed despite retry scheduled, need-action work and source errors | Inconsistent user-facing metrics; different states may be deliberately categorized separately |
| D07 | 00:10–00:17, 01:56 | No bytes transferred, speed, elapsed stage time, host, retry counter, timeout or ETA; flat line progress bar | Directly visible lack of diagnostic/transfer telemetry |
| D08 | 01:58–02:02 | Downloads refresh shows blank `Loading downloads...` for several seconds | Directly visible delay. Not proven permanent hang |

## Site-specific source/adapter failures (P1)
| ID | Time | Provider | Observed error |
|---|---|---|---|
| S01 | ~00:22 | OMG.xxx | `Provider redirected unexpectedly · adapter needs repair`, despite existing visible covers |
| S02 | ~00:28 | Another Porn source immediately preceding Pornhub (name not fully visible) | The same unexpected redirect error |
| S03 | ~00:34–00:36 | CUMS | HTTP 403, authorization or provider adapter required; zero indexed previews |
| S04 | ~00:36–00:40 | EPORNER | `No supported public media on listing; login or adapter may be required`; zero previews |
| S05 | ~00:40–00:48 | Jav Guru | HTTP 403; zero previews |
| S06 | ~00:48–00:52 | JAVIDEO | No supported public media on listing; zero previews |
| S07 | ~00:56 | OneJAV | HTTP 403; zero previews |
| S08 | ~00:56–00:59 | PornTrex | HTTP 403; zero previews |
| S09 | ~01:12,~01:16 | 4KHD / CosplayTele | Source-owned covers visible (12/24 respectively) yet error `No supported public media on listing` also shown. Public cover extractor and separate native index disagree; do not misrepresent as no previews |
| S10 | ~01:20–01:24 | Archivebate | Zero authentic covers, preview check failed; exact cause not disclosed in visible screen |

## Other verified operational gaps
| ID | Time | Evidence |
|---|---|---|
| O01 | ~00:24–00:28 | Pornhub shows 26 public media covers but only 2 indexed original entries; two thumbnail squares render blank |
| O02 | ~01:12–01:24 | Cosplay shows real catalog covers, explicitly marks original download `not verified`; covers alone do not establish downloadable originals |
| O03 | ~01:08 | Home reports **56 registered providers**, **18 native-managed**, **14 index-checked**, **16 awaiting first scan**, **16 errors**. These are status categories, not proof of 56 working downloaders |
| O04 | ~01:28 | Books reports **6 sites, 0 index-checked, 0 indexed items, 0 completed/active/scheduled EPUB collections** |
| O05 | ~01:36–01:52 | MP4Directs books follow/backfill controls are exercised and series/related work fields appear, but no completed EPUB/ZIP or progress receipt is visible. Cannot conclude the task failed solely from this clip |
| O06 | ~00:24–00:30 | First Pornhub covers display empty dark image slots while adjacent covers render; at ~01:16–01:20 some Cosplay image placeholders are dark. Could be unavailable thumbnail URLs or asynchronous rendering |
| O07 | ~00:10,01:56 | `Start queued transfers` remains displayed while one item is already nominally transferring; does not explain whether it can force the blocked worker forward |

## Root-cause boundaries
- A video recording is **not** a network trace or logcat. It cannot establish a specific downloader timeout, DNS failure, extractor binary/version, SSL issue, broken media URL, or device power-management problem.
- A visible preview is **not** a verified downloaded original.
- `0 saved (recent)` does not mean the application has never saved files.
- The transfer screen begins with one video and ends on a different video, so the clip shows a **repeated preparation-stage bottleneck**, not proof of a single unchanged download stuck for 128 seconds.
- The screenshot shows an enabled global `PAUSE` action; do not assume the application is globally paused.

## Backend-only repair acceptance, in priority order
1. Instrument per-job state transitions and stage times (URL validation, extractor, metadata, auth, original URL, HTTP connection, bytes, finalize, receipt). Record exact host and diagnostics without leaking cookies/tokens.
2. Set bounded connect, extractor and idle-transfer deadlines; no indefinite `Preparing / connecting`. Respect provider rate limits and offer safe cancel/retry/resume with exponential backoff.
3. Prevent one blocked metadata job from starving unrelated eligible transfers; preserve explicit episode order only within one actual series.
4. Distinguish `Preparing` from byte-level `Transferring` and accurate `Failed/Need action/Retry scheduled` counts; show speed/bytes and stage heartbeat.
5. Fix site adapters individually from observed errors: redirects, per-site login/authorization (403), listing selectors, actual video-page URL recognition and full original extraction.
6. Avoid presenting failed native indexing as absence of media when a separate public-cover index has already found valid source-owned artwork; preserve provider/error provenance.
7. Verify saved originals (nonempty, expected media MIME/content, checksum when available, saved URI) before counting success.
8. Audit Books/MP4Directs separately for correct request creation, worker state, completion receipts and file output.
9. Regression-test provider fixtures and Android behavior while comparing application UI sources against `checkpoint/downloader-v1.3.48-ui-approved-20261110`. UI is user-approved: do not redesign.

No backend repairs are claimed by this audit.