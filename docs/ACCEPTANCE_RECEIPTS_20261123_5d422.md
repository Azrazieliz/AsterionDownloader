# Asterion Download — 2026-11-23 post-12:03 repair and Samsung receipts

**Acceptance status: OPEN / NOT COMPLETE.** Installation and source tests are verified; restricted original-file downloads and the gallery backlog are not. No claim that an HTTP 401/403 was bypassed, that 4KGirl access was gained, or that an unrelated thumbnail was downloaded as an original.

## Owner-supplied 12:03 evidence
- Previous deployed source SHA at capture: `7698f07e3517912a115df27e96cc3fdac61f8a55`.
- JAV Guru HTTP 401/403; xChina HLS HTTP 401/403 after manifest resolution, zero bytes in latest attempt; JAVIDEO, Avple, UncensoredHentai selected media unavailable; DSYSAV 0 index; 4KGirl 0 gallery covers and listing error.
- Many unrelated preview hosts recorded `UnknownHostException` in a very short time; distinguish transient phone DNS from site-specific denied access.
- Storage counts changed between owner's 11:40 and 12:03 diagnostic (192,540,814 B/3,487 files to 79,908,918 B/1,804 files), largely WebView cache but video IDs also differ. **Cause not established.** Diagnostics are read-only; do not assume a user wipe or data-preservation proof.
- Android historical low-memory exits predate this build; no post-build OOM stress acceptance.

## Read-only DNS evidence
- Bridge result `results/dl-20261123-1206-android-dns-a31c.json` completed, exit code 0.
- Paired Samsung SM-S948B at `192.168.1.116:45921` resolved and reached both `example.com` and `uncensoredhentai.xxx` with approximately 10 ms ICMP latency when checked. Private DNS mode opportunistic, specifier dns.google, HTTP proxy null. This **does not disprove earlier transient DNS failures** and **does not prove a publisher stream is authorized**.
- Read-only install-time probe `dl-1123-install-timestamps-readonly-4c28` failed HTTP 429: unresolved historical storage change, no device mutation.

## Real source changes
- `b745a24ed6278736e9432c1c54e561ba5cccd1e7`: prior domain-scoped poster DNS/5xx cooldown, selected-player UnknownHost and timeout classification.
- `dc31fef4c134ce1e985fdcf849417a660496999e`: 4KGirl transient DNS and empty JS listing errors no longer cached for two hours; conservative bounded delays on true HTTP 401/403.
- `0c8663e5fcaee1f8623b0cab9cf421d96c32e9cc`: exact Android WebView main-frame errors `-2` DNS, `-6` connect and `-8` timeout classified as network retry rather than unsupported original; authentication and unrelated errors unchanged.
- `5d4225495f38ec9a7dfaa7b56a38ca08a7ab0ef0`: Avple selected player `src=blob:` falls back to publisher-signed `data-src` in selected video elements, not related/ad frames.
- The synthetic regression tests for each correction are committed alongside code. No publisher access controls modified.

## Verified source/test/build
- Exact acceptance APK source commit: `5d4225495f38ec9a7dfaa7b56a38ca08a7ab0ef0`.
- GitHub Actions `45164883979` completed `success` (source tests only).
- Existing owner worktree `D:\AsterionDownloaderReleaseVerify_20261120` reused, no new worktree.
- Owner signer command: `commands/active/dl-1123-5d422-owner-release-2ce7.json`, receipt `results/dl-1123-5d422-owner-release-2ce7.json`: complete, exit_code=0, offline Gradle `BUILD SUCCESSFUL in 4m 11s`, original owner signing script.
- Signed APK: `C:\Users\mimim\Downloads\AsterionDownloader-v1.3.85-5d4225495f38-OwnerSigned.apk`, bytes **50,899,084**, SHA-256 `5e7099a1dceecbba118e70f6d58a444407cc0b77627138100cdfd0b14a1ba8c3`.
- Package `com.azrael.pixivdumpsync.acceptance`, versionCode 114, versionName `1.3.85-acceptance`. The shared versionName does NOT identify the APK source; use the staged fingerprint and bridge receipt.

## Verified Samsung installation
- Bridge command `commands/active/dl-1123-install-5d422-verified-72ac.json`; receipt `results/dl-1123-install-5d422-verified-72ac.json`: complete, exit_code=0; staged APK SHA verified; Samsung in-place `adb install -r --no-incremental` returned **Success**, package version rechecked.
- **No uninstall or data clearing** was part of this installation command.
- Read-only post-install on-device `base.apk` checksum / timestamps query `dl-1123-5d422-installed-hash-a9d0` was pending at the time this note was written; do not claim that separate verification until its completed receipt is read.

## Still open
1. Re-test on the real paired Samsung native WSS when existing ChatGPT draft can be preserved, using only authorized originals or permitted samples; verify actual saved bytes, content, length, correct video identity, resume, no preview substitution.
2. JAV Guru, xChina 401/403 and 4KGirl HTTP 403 remain genuine publisher-access possibilities. Do not bypass or fabricate a source. Differentiate unauthorized access from transient DNS.
3. JAVIDEO, Avple, UncensoredHentai still require live unique selected original proofs. Source parser/unit tests do not certify downloads.
4. DSYSAV indexed watch cards and 4KGirl/4KHD full original galleries, missing thumbnails, torrent file list, performer exactness and low-memory/FGS worker stress acceptance all remain open.
5. Owner previously stopped hourly automation; it remains disabled. Do not restart or create a duplicate without explicit instruction.

The recorded source/test/build/install facts are verifiable via existing private GitHub bridge receipts. This is a **durable partial completion checkpoint**, not a completed release.
