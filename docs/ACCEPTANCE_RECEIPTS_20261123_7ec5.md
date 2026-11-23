# Asterion Download — 2026-11-23 source, build, Samsung receipts

**Acceptance status: NOT COMPLETE.** This is a factual work checkpoint, not evidence that any previously failed publisher original was downloaded.

## Release source
- Repo: `Azrazieliz/AsterionDownloader`, repair branch `repair/downloader-global-search-20261121`.
- Exact APK source SHA: `7ec5efedd0f62f51c1a1867ce92e97947a680fa5`.
- Existing PC release checkout: `D:\AsterionDownloaderReleaseVerify_20261120`.
- Changes in this release: JAVIDEO rejects ad/related selected iframe ambiguity but prioritizes one verified STAPE#01 Streamtape iframe over remaining default Javideo frame; Avple permits media `data-src` inside selected player wrappers while retaining ad/preview exclusions; JAV Guru preserves publisher-selected JK Maxstream HLS and can attempt exact isolated JK native recovery after failed non-JK transfer.
- Relevant Git commits: `b4f86d874aab9b4c9b7c15c37f3c86d5f4b6446b`, `38e40c68641a601c62d8e3231477077d7dbd01a6`, `7ec5efedd0f62f51c1a1867ce92e97947a680fa5`.

## Source and signed APK
- GitHub Actions **source-tests-only** run `45156192155`: success (not an Android or publisher end-to-end pass).
- Owner release script: `scripts/build_owner_signed_acceptance_1385.ps1 -ExpectedCommit 7ec5efedd0f62f51c1a1867ce92e97947a680fa5`.
- Control receipt: `results/dl-20261123-1132-build-7ec5-owner-bd91.json`, complete, ok, exit 0; offline Gradle tests and assembled APK reported `BUILD SUCCESSFUL in 4m 48s`; original owner signer verified.
- Staged signed APK: `C:\Users\mimim\Downloads\AsterionDownloader-v1.3.85-7ec5efedd0f6-OwnerSigned.apk`.
- Signed APK bytes: **50,891,356**; SHA-256: `28c764e370dc88ff039cc8648f16ddce7064fdf68615b4cd47177f26e050fe2c`.
- Verified package/version: `com.azrael.pixivdumpsync.acceptance`; version code 114, version name `1.3.85-acceptance`.

## Samsung Android 17
- Device: Samsung SM-S948B, existing native Wi-Fi Control bridge; the phone was reachable over pre-paired ADB `192.168.1.116:45921` during installation.
- In-place data-preserving installation: `results/dl-20261123-1138-install-owner7ec5-short-a761.json`, complete, ok, exit 0; staged hash verified; ADB `install -r --no-incremental` returned `Success`; version verified.
- Launch: `results/dl-20261123-1139-launch-smoke7ec5-f611.json`, complete, exit 0; `AsterionHomeActivity` foreground and Android process PID reported.
- Native WSS UI: `results/dl-20261123-1141-downloads-ui-7ec5-9b3e.json`, complete; Downloads page displayed; zero saved video originals, two retained video failure rows (JAVIDEO, Avple), one failed torrent, five gallery jobs queued. Historical failure rows are **not** proof the revised source re-failed after installation; no original-download retest performed on restricted external content.
- Post-launch crash scan: `results/dl-20261123-1142-crashlog-postupgrade-e412.json`, complete, exit 0; running process PID 1562; no matching FATAL / OOM / FGS-exception entries in the sampled logcat slice. Not a stress test.

## Still unverified / open
- JAV Guru and xChina selected originals: historical HTTP 401/403; no access-control bypass. JAV Guru's new JK fallback needs live proof; xChina remains unresolved.
- JAVIDEO, Avple, UncensoredHentai: full unique publisher-selected original bytes / correct file content and duration not verified. Existing failed records must not be reclassified as success.
- PimpBunny: historical saved-file-missing receipt; 4KGirl/4KHD gallery queue not complete; torrent 0-peers, no verified bytes in the owner's recording; performer `bella Alice` behavior and preview/502 still need live acceptance.
- Foreground-service quota, low-memory and worker reliability need stress tests beyond one launch and logcat sample.
- The owner's previously stopped **Finish Asterion Download** hourly automation remains disabled; do not re-enable without owner request. No new bridge or pairing installed.
