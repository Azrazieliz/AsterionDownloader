# Asterion Download — 2026-11-23 v1.3.88 signed Samsung receipt and remaining acceptance

**Status: versioned APK installed successfully; full app acceptance remains OPEN.**

## Release identity and data preservation
- Exact packaged code SHA: `1106c2c906981238490ab7be1df80753207a7fb4`.
- Changes since v1.3.87: only the source-specific error classifier for masked 4KGirl HTTP 403, its isolated regression test, and Android release identity bump. No additional providers retired, no existing provider configs or media removed.
- `app/build.gradle.kts`: `versionCode = 117`, `versionName = "1.3.88"`. Acceptance package `com.azrael.pixivdumpsync.acceptance`, final `versionName = "1.3.88-acceptance"`.
- Retained original Windows release worktree `D:\AsterionDownloaderReleaseVerify_20261120`, owner DPAPI signer and original signing-certificate pin. No new worktrees, helpers, pairing, or unrelated app mutations.
- Previous explicit owner automation stop remains in effect; no reactivation.

## Actual source defect and evidence
- Windows Control command `dl-20261123-1400-public-index-probe-d2c6`: `status=complete`, `exit_code=0`, `https://www.4kgirl.com/` returned HTTP **200** with zero links.
- Windows Control command `dl-20261123-1404-index-body-signature-2a31`: `status=complete`, `exit_code=0`, exact response length **138** chars, embedded **403 Forbidden**, zero GridWP/post anchors.
- `KyoraCreatorPreviews.kt` had separately parsed this disguised denial as zero galleries and cached `No gallery thumbnails exposed by listing`, although other source workers detect masked 403. Applied `requirePublisherListing` to both raw HTML and public WebView fallbacks. Source commit `27d8f0911a6eaeda5dd29a9267d212d500487521`.
- Regression test commit `c5c7095c8ee34364c2eac731f0917fc037360683`: masked 403 maps to publisher access denial, unrelated 4KHD/empty pages unchanged. This is correct diagnosis/reporting; **4KGirl originals are not accessible without the publisher granting normal access**.
- Windows Control command `dl-20261123-1400-public-index-probe-d2c6`: `dsysav.com/en` returned HTTP **200**, approximately **10,364** HTML chars with **zero watch anchors**, a Next.js shell. Existing WebView indexer requires real publisher `/en/watch?v=<ID>` links. No synthetic cards or URLs may be counted as success.
- The metadata-only probe (`dl-1123-1424-dsysav-publiccatalog-probe-d4c9`) was rejected with **HTTP 403 in the Control command receipt**, without a Windows execution log proving whether the publisher API was contacted. Thus the publisher catalog API's real status is **unknown**, not verified denied. No restricted media was downloaded or access-control bypass attempted.
- 4KHD public `/pages/cosplay` listing returned HTTP 200 with 44 anchors; one first-party publisher bridge `https://m.4khd.com/CIY30g` returned HTTP 200 and a TeraBox reference. Native parser supports exact first-party bridges, but **no completed original archive / verified file contents or file size** has been observed. Its true TeraBox share access and original acquisition remain OPEN.

## Build/test/signing
- GitHub source-test run `45181428001` completed `success` on `8444aa135990...` (includes code repair and version bump, before owner build script change).
- Bridge build `commands/active/dl-1123-1417-ownerbuild-v1388-cc90.json`, receipt `results/dl-1123-1417-ownerbuild-v1388-cc90.json`: **status=complete; exit_code=0**, offline Gradle `BUILD SUCCESSFUL in 5m 16s`, 81 actionable tasks (32 executed, 49 up-to-date), unit tests + lint + assembled acceptance APK, **original owner certificate matched**.
- Staged signed APK: `C:\Users\mimim\Downloads\AsterionDownloader-v1.3.88-1106c2c90698-OwnerSigned.apk`.
- Staged bytes **50,902,696**; SHA-256 `eb252faf640d2f7c25b55501d14f97dfc43502a9ac4269f860574f8f6e3cc6ff`.
- Version read from signed Android package: `com.azrael.pixivdumpsync.acceptance`, `versionCode=117`, `versionName=1.3.88-acceptance`.

## Real Samsung update
- Bridge command `commands/active/dl-1123-1428-install-v1388-owner-7bd3.json`; receipt `results/dl-1123-1428-install-v1388-owner-7bd3.json`.
- Paired Samsung SM-S948B. Before update code **116**, staged SHA verified, `adb install -r --no-incremental` returned **Success**, after update code **117**, versionName **1.3.88-acceptance**; **status=complete; exit_code=0**.
- No `pm clear`, no uninstall, no delete, no unrelated app mutation, no changed provider list.
- The in-place app installation does not prove original downloads work; no active user ChatGPT draft was dismissed for UI testing.

## Acceptance still open — do not claim completion
1. 4KGirl actual authorized gallery/originals: upstream masked access denial still present, now correctly diagnosed.
2. DSYSAV actual first-party watch cards: public HTML shell empty; browser-rendered catalog and metadata endpoint remain unverified due lack of completed source evidence, no manufactured catalog entries.
3. 4KHD entire native selected original archive: first-party bridge reachable, completion/contents not validated.
4. JAV Guru, xChina HTTP 401/403; JAVIDEO, UncensoredHentai selected originals; no bypass, no preview substitutions.
5. Crash, foreground-service and low-memory stress, thumbnail HTTP 502/404, exact performer, torrent files, general search, end-to-end device acceptance.

**Do not retire further providers, do not delete source data or saved originals, and do not present build, poster or listing counts as verified downloaded originals.**
