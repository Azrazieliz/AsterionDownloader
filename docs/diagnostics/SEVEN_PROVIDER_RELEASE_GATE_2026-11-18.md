# Seven-provider final-build gate — 18 November 2026

## Owner acceptance contract

**No Android APK assembly, signing, transfer, or installation until all seven selected actual originals have passed complete-file PC tests and protected-provider regressions.** This is the owner's final authorized build. Preserve the original Kuroha and acceptance package identities, DPAPI-protected signing key, databases, queues, creator IDs, and all frozen working-provider implementations. Never count an iframe, HTTP 200, metadata, manifest, poster, partial file or diagnostic exit 0 as a complete video.

The seven and only seven currently failing providers: Jav Guru, JAVIDEO, HelloPorn, Pornhub, HentaiHaven, MP4Hentai and UncensoredHentai. Archivebate is not an eighth failure.

## Authoritative executed PC gate

- Worker: existing Asterion Control private Git bridge, PC worktree `D:\AsterionDownloaderRepair_20261116`.
- Strict command ID: `dl7-final-strict-gate-pc-20261118-1633-a02`, tested repository `95c2621ddb6c8babd82dc584f90f709b28a9466b`. **Final exit 1; no APK authorized.**
- Complete actual first-party Pornhub original: **161,248,728 bytes**, FFprobe duration **2,551.033 s**, video validated, SHA-256 prefix `e071e52328dc0cf99bd51544`, script exit 0.
- Complete selected HentaiHaven episode: **111,592,685 bytes**, FFprobe duration **991.936 s**, audio+video validated, SHA-256 prefix `a0e3623439dbba11b517fbac`, script exit 0. The separately completed earlier test had the same size/duration with a different mux SHA prefix.
- **Jav Guru:** exact selected MXGS-1452 signed STREAM SB original partial transfer timed out after 600 s (exit 124); not complete. Bounded PC probe `dl7-final-javguru-segment-proof-20261118-1721-a10` **exit 0** verified the exact signed master HTTP 200 (1,829 B, three variants), one selected variant HTTP 200 (399 segments; about 3,994 seconds), and first true MPEG-TS segment HTTP **206**, 4,096 bytes. This is source identity and bytes, **not a complete selected movie**, and does not open the build gate.
- **JAVIDEO:** actual selected Play->`play.javideo.net/embed/...` displays no resolved currentSrc/media, exit 4. The diagnostic now outputs only redacted JS structure, not ad sources.
- **HelloPorn:** exact selected VOE iframe connection refused (`voe.sx:443`); no video bytes.
- **MP4Hentai:** selected UpFiles free form returns interactive file-captcha/HTML, not actual media (exit 2).
- **UncensoredHentai:** first-party NHPlayer selected server discovered (diagnostic exit 0), but no completed source or bytes.
- **Gate summary:** exactly **2/7** complete selected originals; failed. `seven_provider_strict_pc_gate.py` correctly refuses to count source-only probes for four providers.

## Safe PC capacity restored

- Read-only PC worker result `dl7-final-release-readonly-pc-state-20261118-1631-a01`: C free **8,759,332,864 B**, D free **17,838,309,376 B**, tracked worktree clean. Previous request to remove one owned C: scratch returned HTTP 429 and was **not** confirmed successful; scratch existed at this read-only check (210,721,999 B). No unrequested cleanup is justified.
- Full-media scripts stage only within existing `D:\AsterionControlRelease\diagnostics` with mandatory free-space guard; temporary successful media deletes on test completion.

## Source changes since prior checkpoint

1. `ef56ad3`: approve publisher-selected `hls_master` alongside `hls` within Pornhub's existing tightly constrained selected watch and approved CDN parser; `7f7909d` adds a regression.
2. `0ac072e`, `e6b123c`: restrict actual PC-proven MPEG-TS, separate video/audio and Matroska mux for Pornhub to publisher-selected original HLS only, leaving all unrelated provider settings unchanged; `fc49c2c` tests host/watch scope.
3. `853cbc7`, `55a46b4`: enhanced exact JAVIDEO first-party browser source diagnostics with no secret media URLs.
4. `8d1bb20`: diagnostic for the exact Jav Guru SB signed HLS manifest and one publisher-declared segment, no substitute media and no complete-download claim.

## Verification pending and next actions

- Source-only PC regression command `dl7-final-source-regressions-20261118-1655-a06`: **exit 0, BUILD SUCCESSFUL in 7m 7s**, 24 actionable tasks (6 executed, 18 up-to-date), ran `:app:verifyFrozenVideoProviders :app:testAcceptanceUnitTest` on source `55a46b44ec2141d4c88d87cc82fafbdf456955bb`. Kotlin emitted one non-fatal nullable receiver warning in `ActualProviderIntegrationTest.kt:50`; no APK built/signed.
- When source tests complete, run the new Jav Guru bounded transport probe and fix only observed publisher-source defects. The other four sites require an actual eligible selected original or ordinary authorized site verification/network access; do not invent media URLs, bypass CAPTCHA/DRM/region restrictions or pretend iframe resolution is a download.
- **Do not build or transfer anything while only 2/7 PC originals are verified.** If and only if all seven complete files and frozen-provider+JVM tests later pass, perform exactly one owner-signed in-place APK build with retained key/certificate; transfer via existing Asterion Control, verify SHA/package and Android 17 crash behavior.

Canonical source: `repair/downloads-nine-providers-backfill-diagnostics-20261116`; source and validation are separate evidence.

## Final authorized-build continuation — 18 November (second pass)

The owner repeated: **this is one single final authorized APK build**. Complete seven real selected-origin Windows PC downloads, run frozen-provider and Android source tests, then and only then assemble and owner-sign exactly one in-place acceptance APK; verify matching package/cert/SHA and transfer it through the existing Asterion Control bridge. **No final APK has been built or transferred in this pass. Do not weaken this gate.** Do not rename packages or alter Kuroha, queues, archives, other working providers or signing key.

### Fresh confirmed evidence and source changes

- Reconciled repository head with exact last known full-file strict PC run: **2/7** verified, Pornhub 161,248,728 B / 2,551.033 s, HentaiHaven 111,592,685 B / 991.936 s. Five remain Jav Guru, JAVIDEO, HelloPorn, MP4Hentai, UncensoredHentai.
- Existing Windows source-only regression `dl7-final-source-regressions-20261118-1655-a06` completed exit 0: `verifyFrozenVideoProviders` and `testAcceptanceUnitTest` **BUILD SUCCESSFUL**, tested earlier `55a46b4`. This is not an APK or device runtime verification.
- Publisher-selected Jav Guru MXGS-1452 SOURCE: the correct signed SB master lists **480p/617332 bps, 720p/1327368 bps, 1080p/3076469 bps**; all are **6161.2 seconds, 616 fragments**. Read-only PC probe `dl7-final-four-player-deep-probes-safe-20261118-1800-f12` returned exit 0; this is only evidence of a publisher-selected full-length source, not downloaded completion.
- `584f5e6` and `3054a9b`: specifically fix MP4Hentai repeated `host did not expose a verified download in five steps` classification from `OTHER` (retry loop) to terminal `UNSUPPORTED`, with regression; its actual CAPTCHA continues to classify as `AUTH`. **Not a completed MP4Hentai media transfer.**
- `8fd6f87`: exact Jav Guru owner SB rendition/length diagnostics with no guessed media.
- `8cf354e` and `7dcc3d2`: Jav Guru PC-only complete-file verifier now uses bounded 8-lane fragments, temporary guarded D: staging, redacted periodic progress, **strict playlist-duration correspondence and audio+video FFprobe and SHA**. Specifically checks 480p original full-length rendition, not trailer or preview. This does **not** lower selected Android quality or change any frozen implementation.

### Active PC commands whose final receipts MUST be retrieved, not guessed

- `dl7-javguru-480p-eight-lane-full-20261118-1818-f14`: source `7dcc3d26547ddc1ae8432a8ff6463cc0ff8bc032`, selected MXGS-1452 480p 6161-second original FFprobe/SHA full-file test. Log `D:\AsterionControlRelease\diagnostics\javguru_selected_480p_eight_lane_full_20261118.log`. `accepted`/running is pending; ONLY a final `COMPLETE_SELECTED_PC_FILE` plus verified duration/bytes allows counting this as a Jav Guru pass. If it finishes or errors, inspect actual output, avoid duplicate simultaneous long downloads.
- `dl7-current-nhplayer-javideo-structured-20261118-1823-f15`: read-only live selected source diagnostics for UncensoredHentai NHPlayer and JAVIDEO actual Play -> first-party embed. Inspect their final JSON structure; do not infer media from a player shell.
- Source-only GitHub Actions runs **44353314423** (`3054a9b`) and **44353306258** (`584f5e6`) were last observed `in_progress`; check their final conclusions for new MP4Hentai regression. Workflow is explicitly **no APK**.

### Other still-blocked providers

- JAVIDEO: selected first-party player exists but browser `video.currentSrc` has no verified playable media; cannot treat parent autoplay video/advert as selected.
- HelloPorn: real selected VOE `voe.sx` connection refused; headless Edge rendered **0 DOM bytes**, not evidence of a working player.
- MP4Hentai: selected UpFiles free form leads to genuine CAPTCHA; Mirrored.to has only public JS link controls with no verified complete media. No automated CAPTCHA bypass or guessed link is permitted.
- UncensoredHentai: selected NHPlayer first server and JavaScript/AJAX observed but no complete attributable original. Honor ordinary legitimate site verification if required.
- Jav Guru: previous 600-second full-file test timed out; new bounded 8-lane full-file check is pending. Its small 206 MPEG-TS fragment was real, but not sufficient for pass.

### Next conversation execution instructions

1. Fetch latest repair branch Git SHA and this exact checkpoint before doing anything; do not start over.
2. Retrieve the two Control results above, classify complete vs partial for each; retrieve the two source-only CI conclusions.
3. If Jav Guru new full file passes, record complete byte/duration/SHA receipt and verify exact Android-selected manifest transfer settings. If it fails, use exact progress log and selected publisher route diagnosis, not another speculative APK.
4. Work only on the five unresolved provider specifics; replay strict **seven complete-file PC** test and exact protected-provider regressions after repairs; no unrelated provider/UI/DB changes or substitutions.
5. **One final owner-signed build and phone transfer only when all seven pass**, same package/signature, existing Control native bridge. If even one cannot furnish a complete selected original without owner-completed access challenge or network availability, leave final build blocked and report the exact external dependency rather than claiming a working release.
