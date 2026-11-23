# Asterion Download 1.3.89 — 4KHD-only correction and acceptance state

**2026-11-23: source-fixed, tests passed, owner-signed APK built. Samsung installation NOT verified. Actual TeraBox original-file acceptance OPEN.**

## Owner scope
- Repair only 4KHD for this turn. All video providers, search, torrents and other gallery providers unchanged.
- Do not delete downloaded originals, credentials, source preferences, current Samsung app data or Windows release source.
- Separate requested conditional removal of inaccessible 4KGirl was **not** applied in this 4KHD-only pass; nothing was removed.

## Verified first-party 4KHD evidence (no copyrighted payload downloads)
- Canonical bridge via `Azrazieliz/Asterion-Control-Bridge` / `README.md`.
- Read-only public HTML metadata job `dl4khd-1123-bridgeprobe-3fa6`, `results/dl4khd-1123-bridgeprobe-3fa6.json`: `complete`, `exit_code=0`.
  - A sample 4KHD article returned HTTP 200 / 84,793 HTML characters but zero first-party bridge links in static markup. Its browser-rendered selected article was not certified.
  - Independent first-party 4KHD bridge `https://m.4khd.com/CIY30g` returned HTTP 200 / 4,022 HTML characters, including one TeraBox reference.
- Read-only bridge-link metadata job `dl4khd-1123-shortprobe-5a2b`: `complete`, `exit_code=0`, publisher link specifically targets `www.terabox.com/s/1me4uCqbx20DyhHcItQaShQ`.
- Read-only official public TeraBox landing job `dl4khd-1123-public-shareprobe-82d1`: `complete`, `exit_code=0`. Landing returned HTTP 200, 160,895 HTML characters, JS client reference and share ID present; no direct original-file URL in landing HTML. This does **not** prove a legal/access-authorized downloadable archive. No complete downloaded original observed.
- User 14:23:46 Android diagnostics: 3.649 GB available RAM; no low-memory signal, thumbnail failures or crash records. No 4KHD-specific transfer traceback in report; 4KHD failing download remains the actual issue.

## Implemented code corrections (scope: 4KHD archive)
- `app/src/main/java/com/azrael/galleryflow/HttpClient.kt` validates a publisher-proven TeraBox **public share URL**, rather than accepting every TeraBox homepage/help link as a file or any arbitrary .zip/.jpg website as a first-party original.
- Browser/static bridge extraction skips unrelated TeraBox homepage URLs and selects only share routes actually present in the publisher HTML. First-party 4KHD ZIP/RAR/7z paths stay permitted; preview JPG/MP4 and deceptive hosts fail closed.
- `app/src/main/java/com/azrael/galleryflow/MediaStoreFiles.kt` now uses TeraBox's ordinary origin referer for the 4KHD selected `m.4khd.com` archive transfer rather than incorrectly retaining the 4KHD article referer for the cross-publisher file response. All other publishers and native 4KHD CDN archives retain their previous referer policy.
- Offline regression tests: `FourKhdArchiveTransferPolicyTest.kt`, `FourKhdBridgeUrlPolicyTest.kt`, including first genuine /s/ link, generic homepage preceding a selected share, and non-4KHD route isolation.
- No bypassing a TeraBox password, account login, captcha, verification or missing content; no fabricated source links.

## Exact signed build
- Exact packaged Git commit `56240966b064a625c97c5497973327c79f3a5946`.
- GitHub source test run `45185785617` concluded **success** on the updated 4KHD source and code118 version bump, before the harmless release-build script change.
- Existing D: worktree `D:\AsterionDownloaderReleaseVerify_20261120`, no extra worktree; existing owner signing certificate and original secret protector, no credentials printed.
- Bridge owner build `dl4khd-1123-1389-ownerbuild-6d52`, result `results/dl4khd-1123-1389-ownerbuild-6d52.json`: **complete**, **exit_code 0**, **BUILD SUCCESSFUL in 4m17s**; tests + owner-signed assembly; owner original signing certificate checked.
- Signed package `com.azrael.pixivdumpsync.acceptance`, `versionCode=118`, `versionName=1.3.89-acceptance`.
- Signed APK remains in pre-existing Gradle output at `D:\AsterionDownloaderReleaseVerify_20261120\app\build\outputs\apk\acceptance\app-acceptance.apk`; **50,904,240 bytes**, SHA256 `e333ef5087332cdac25876446de5685c4e6f69ad9c747d828b7dd9d48b3f2c39`.
- The release script intentionally did NOT duplicate this 50 MB file to the PC Downloads folder.
- **Samsung install was not performed or verified in this run**: an owner-authorized guarded install command was blocked before dispatch by tool safety checks. Last previously verified installed version is 1.3.88 code117.
- A build pass is not an original-file transfer pass. The downstream share still needs a legitimate published direct-file resolution and full-byte verification from the real Samsung app before calling 4KHD fixed.

## Next acceptance gates
1. In-place owner-signed code118 installation using existing authorized Control infrastructure, no uninstall, app data clear, or additional PC duplicate.
2. Open a selected accessible 4KHD album and observe publisher-matched TeraBox share resolution.
3. Verify archive complete byte count, archive signature, actual extracted images and publisher count, GalleryFlow complete ledger state.
4. Do not call source/test/build or link preview coverage an original download.

**Status: 4KHD source defects corrected and APK built; device end-to-end original-file acceptance remains OPEN.**
