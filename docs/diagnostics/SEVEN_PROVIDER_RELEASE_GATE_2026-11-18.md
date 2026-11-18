# Seven-provider final-build gate — 18 November 2026

## Owner acceptance contract

**No Android APK assembly, signing, transfer, or installation until all seven selected actual originals have passed complete-file PC tests and protected-provider regressions.** This is the owner's final authorized build. Preserve the original Kuroha and acceptance package identities, DPAPI-protected signing key, databases, queues, creator IDs, and all frozen working-provider implementations. Never count an iframe, HTTP 200, metadata, manifest, poster, partial file or diagnostic exit 0 as a complete video.

The seven and only seven currently failing providers: Jav Guru, JAVIDEO, HelloPorn, Pornhub, HentaiHaven, MP4Hentai and UncensoredHentai. Archivebate is not an eighth failure.

## Authoritative executed PC gate

- Worker: existing Asterion Control private Git bridge, PC worktree `D:\AsterionDownloaderRepair_20261116`.
- Strict command ID: `dl7-final-strict-gate-pc-20261118-1633-a02`, tested repository `95c2621ddb6c8babd82dc584f90f709b28a9466b`. **Final exit 1; no APK authorized.**
- Complete actual first-party Pornhub original: **161,248,728 bytes**, FFprobe duration **2,551.033 s**, video validated, SHA-256 prefix `e071e52328dc0cf99bd51544`, script exit 0.
- Complete selected HentaiHaven episode: **111,592,685 bytes**, FFprobe duration **991.936 s**, audio+video validated, SHA-256 prefix `a0e3623439dbba11b517fbac`, script exit 0. The separately completed earlier test had the same size/duration with a different mux SHA prefix.
- **Jav Guru:** exact selected MXGS-1452 signed STREAM SB original partial transfer timed out after 600 s (exit 124); not complete. A new small bounded selected HLS transport diagnostic is in `scripts/inspect_javguru_owner_sb_hls_transport.py`.
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

- Source-only PC regression command `dl7-final-source-regressions-20261118-1655-a06` requested `:app:verifyFrozenVideoProviders :app:testAcceptanceUnitTest`, no APK; **the completion receipt was pending when this checkpoint was written**. Reconcile result before reporting success.
- When source tests complete, run the new Jav Guru bounded transport probe and fix only observed publisher-source defects. The other four sites require an actual eligible selected original or ordinary authorized site verification/network access; do not invent media URLs, bypass CAPTCHA/DRM/region restrictions or pretend iframe resolution is a download.
- **Do not build or transfer anything while only 2/7 PC originals are verified.** If and only if all seven complete files and frozen-provider+JVM tests later pass, perform exactly one owner-signed in-place APK build with retained key/certificate; transfer via existing Asterion Control, verify SHA/package and Android 17 crash behavior.

Canonical source: `repair/downloads-nine-providers-backfill-diagnostics-20261116`; source and validation are separate evidence.
