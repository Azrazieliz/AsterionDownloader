# Asterion Download — uniquely versioned Samsung release receipt (2026-11-23)

**Release package identity and installation verified. Functional original-download acceptance remains OPEN.**

The prior Android acceptance APK was repeatedly rebuilt with an unchanged `1.3.85-acceptance` / versionCode `114`, preventing the owner from identifying the current APK in Settings. This release corrects that mistake; future owner releases must increment both the displayed base version and the Android code.

## Final source and source-only tests
- Release branch: `repair/downloader-global-search-20261121`
- **Exact source commit:** `c4b13e482199a2544e1406972557f5d935d53791`
- `app/build.gradle.kts`: `versionCode = 115`, `versionName = "1.3.86"`; acceptance build type has `versionNameSuffix = "-acceptance"` and unchanged `.acceptance` package suffix.
- Owner signing script `scripts/build_owner_signed_acceptance_1385.ps1` updated to verify package, code `115`, version `1.3.86-acceptance`, and unique SHA-suffixed staging name; original signer and certificate pin preserved.
- GitHub Actions source test workflow `45167781856`: `completed`, `success` (source SHA `2e3ddc6dba49...`, same Android production code, before PowerShell quote correction).
- The initial Windows versioned build was blocked by PowerShell output-filename quote error (receipt `dl-version-1386-owner-build-20261123-a13c`, exit code 1). Corrected at final commit `c4b13e48`. No APK from failed attempt was installed.

## Successful final owner-signed build
- Bridge command: `commands/active/dl-version-1386-owner-build-fixed-20261123-b51e.json`
- Receipt: `results/dl-version-1386-owner-build-fixed-20261123-b51e.json`
- Async process job: `aee3bb1a184b486ab33df87b0bb362da`
- **`status=complete`, `exit_code=0`; offline Gradle `BUILD SUCCESSFUL in 6m 25s`, 81 actionable tasks.**
- `ORIGINAL_CERTIFICATE_MATCH=true` and signed APK package was verified via Android build tools.
- Badge: `com.azrael.pixivdumpsync.acceptance`, **versionCode `115`**, **versionName `1.3.86-acceptance`**.
- Signed staged APK: `C:\Users\mimim\Downloads\AsterionDownloader-v1.3.86-c4b13e482199-OwnerSigned.apk`
- Staged APK size: **50,899,084 bytes**
- SHA-256: `34729181ae6d6204701348d20453c58ab653c4c669e97d361f5c794e2999fd62`

## Samsung installation
- Paired Samsung `SM-S948B` Android 17 through the existing Control bridge, no new transport or infrastructure.
- First guarded installation attempt `dl-version-1386-install-20261123-bd82` failed with private bridge HTTP 429; no successful installation claimed.
- Guarded retry `commands/active/dl-version-1386-install-retry-20261123-e42a.json`
- Definitive receipt: `results/dl-version-1386-install-retry-20261123-e42a.json`
- **`status=complete`, `exit_code=0`, `BEFORE versionCode=114`; ADB `install -r --no-incremental` returned `Success`; `AFTER versionCode=115; versionName=1.3.86-acceptance`.**
- Staged SHA-256 and paired Samsung model checked before the update. No uninstall, `pm clear`, other-app changes or data deletion occurred.
- Owner can identify the installed build through Android Settings → Apps → Asterion → app version `1.3.86-acceptance` (code `115`). Android version labels are no longer identical to the old v1.3.85 build.

## Remaining acceptance blockers
No source-test or package-installation receipt constitutes proof that restricted original files were delivered successfully. Keep JAV Guru/xChina authorization failures, JAVIDEO/Avple/UncensoredHentai unique selected originals, DSYSAV/4KGirl catalog problems, memory/FGS, gallery originals, search exactness and thumbnails OPEN until independently accepted on the actual Samsung. Never bypass 401/403 or use thumbnails as substitutes.

This records an **implemented, source-tested, owner-signed, staged and Samsung-installed version bump**. It does not close the global completion cycle.
