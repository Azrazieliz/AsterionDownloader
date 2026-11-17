# Asterion Downloader — 17 November 2026 repair continuation

**Canonical source:** `Azrazieliz/AsterionDownloader`, branch `repair/downloads-nine-providers-backfill-diagnostics-20261116`.
**Last code/test commit of this continuation:** `f8b899d82527e12352af99cbfb2a4adbf1434439`.
**Release status:** SOURCE CHANGES ONLY; NO NEW APK BUILT OR INSTALLED FROM THESE COMMITS; Android provider acceptance NOT VERIFIED.

## Implemented after cbbd237

- `de46eb15c`: HelloPorn resolver no longer accepts static/generic article media when the exact selected VOE iframe fails. HentaiHaven episode resolver no longer falls back to video tags or Next.js unrelated recommendation preview MP4s; it requires the selected episode's own authoritative JSON-LD HLS. Existing series-vs-episode safety retained.
- `d843c7313`, `c4c5fedbe`: pure Kotlin regressions for HentaiHaven series/episode identity, selected JSON-LD VideoObject HLS, hostile host rejection and ambiguous source rejection. Fixtures corrected before verification.
- `a320495ee`, `92bc2d573`: `WebDiscovery.page()` never creates loose, anonymous HTML5 media download entries on Archivebate, HelloPorn, HentaiHaven or JAVIDEO; those sites require selected-provider player ownership. Existing other video sites preserve normal discovery behavior.
- `60b6f9cdd`, `780f24e0c`: test strict-player discovery gate, ensure unaffected providers stay eligible; Kotlin interpolation corrected.
- `3ae02a0a6`, `15c3d7edc`: `VideoHlsDispatch.needsRuntimeBeforeSource` now primes the already embedded extractor for exact HentaiHaven episode pages before retrieving short-lived selected HLS; corresponding regression assertion added.

- `4f5ee1d24`, `f8b899d82`: HentaiHaven series directory resolution now handles genuine relative `episode-N` anchors even when the selected series URL lacks a trailing slash. Offline regression fixture uses that exact shape.

## Independent source-only CI gate

- Commit `14e2331f3` added the active repair branch to the existing `.github/workflows/downloader-one-build-preflight.yml`. It only runs frozen-provider verification and Pilot/Acceptance unit tests, not APK assembly, signing, Android installation or publisher acceptance.
- GitHub Actions run [44152377019](https://github.com/Azrazieliz/AsterionDownloader/actions/runs/44152377019) was queued and then in progress for head `14e2331f3` at the last observed check. **No final conclusion obtained at this checkpoint**. Do not claim the full regression suite passed until the completed job reports success.

## Regression gate root cause and correction

- Source-only CI run [44152377019](https://github.com/Azrazieliz/AsterionDownloader/actions/runs/44152377019) on `14e2331f3`: **FAIL**, with `668 tests completed, 1 failed, 13 skipped`. The only failure was `SyncJobServiceAndroid17RegressionTest.scheduledGallerySyncNeverLaunchesForbiddenForegroundService`; the Kotlin production source compiled and `:app:verifyFrozenVideoProviders` executed.
- Root cause is a **false-positive source-string check**, not proof of an Android 17 foreground-service crash. `SyncJobService.kt` has historical KDoc text containing `startForegroundService()`, while the actual `onStartJob` executes `executor.submit` within JobScheduler and calls `jobFinished(params,false)` after completion.
- Commit `5ffb471354389991373169ef3c182d897ee67c17` updates **only the regression test** to ignore block/line comments before checking for forbidden API calls. The actual JobService and all frozen provider implementations are unchanged.
- Source-only CI rerun [44153155251](https://github.com/Azrazieliz/AsterionDownloader/actions/runs/44153155251) launched for `5ffb471`; final result should be read directly before marking green. This test is not a Samsung runtime acceptance.

## Verification evidence (never inflate)

- Existing Asterion Control private Git bridge was used; owner Windows repair worktree `D:\AsterionDownloaderRepair_20261116` initially at `26d1cab` with only untracked `.kotlin/` and `scripts/__pycache__/`. A fast-forward to `c4c5fed` completed, preserving tracked source, and a subsequent PowerShell `where.exe gradle` missing command caused the wrapper job exit 1 **after** successful fast-forward (not a Git failure).
- Windows `pc_command` accepted regression job `3339375450ba4dc392b52b4b86504372` for `:app:verifyFrozenVideoProviders :app:testAcceptanceUnitTest` on `c4c5fed`. An observed intermediate `pc_job_status` showed the frozen-provider check and Acceptance Kotlin/Java compilation complete and the acceptance unit tests running. **Final test exit not obtained.** Do not label this run passed.
- Later changes through `f8b899d82` are **not** compiled or tested as a unit. No signed acceptance package has been created from these patches and no new Android APK transferred.
- Read-only Git repository inventory of the Control bridge showed 6,818 active command files and eight pending receipts at last check; GitHub's ordinary directory listing displays only 1,000. Control transport source actually uses Git checkout, not REST listing, so the 1,000 cap alone does NOT prove the cause. Earlier bridge commands completed; newest health, NHPlayer and regression-status requests lacked receipts. Preserve queued IDs, avoid duplicate side effects or forced service recreation.
- Current user-visible latest fully signed transfer prior to these changes was v1.3.73 owner-signed, receipt in `docs/diagnostics/ACCEPTANCE_V1_3_73_DELIVERY_2026-11-17.md`. Do not infer installed version from the transferred file name.

## Still open, phone acceptance required

Seven user-reported failing priority video providers: Jav Guru, JAVIDEO, HelloPorn, Pornhub, HentaiHaven, MP4Hentai and UncensoredHentai. Each needs an exactly selected original, final nonzero bytes, validated playable duration, correct source identity, preserved MediaStore receipt, repeated successful session, no crash. The new fixes do not assert any complete transfer.

Other unresolved functional obligations: real simultaneous webpage transfers under current phone memory, Pixiv sync/backfill and Kuroha legacy UI parity, Queue/Pause/Resume/Discard, X-following false imports, real torrent transfer and trusted release, full FreeWebNovel EPUB, cosplay/site original counts, Koikatsu full-aspect cards, stability. Preserve working Toonily and MangaRead ZIP, frozen video providers, all user data and signing identity.

## Next safe implementation gate

1. Obtain final worker result for regression job `3339375450ba4dc392b52b4b86504372` before reporting it.
2. Fast-forward the existing Windows repair worktree to the exact latest `f8b899d82` or newer code SHA without discarding unrelated untracked files.
3. Run `:app:verifyFrozenVideoProviders :app:testAcceptanceUnitTest`, correct failures, then obtain exact owner-signed artifact from the retained keystore; do not replace or uninstall the old acceptance app or use a CI debug signer.
4. Test each failing provider on the Samsung through existing Control native bridge and record actual local complete-file receipts. External authentication/CAPTCHA/region blocks are not fixed by pretending a homepage, player, MP4 header or preview is an original.

Never claim a release success from a code commit, HTTP 200, CI fixture, APK transfer or accepted queued worker job alone.
