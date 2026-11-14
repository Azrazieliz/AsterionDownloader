# Asterion Downloader — in-app updates and gallery repair

Date: 2026-11-09
Status: **Source implemented; local compile, unit tests and APK verification passed. Device installation / visual acceptance not yet passed.**

## Non-destructive continuation
- Preserve the canonical repository, all databases, source records, credentials, queues and existing installed packages.
- Do not uninstall a previous Downloader/Kuroha/Nocturne to bypass Android signing checks.
- Do not substitute a differently signed debug APK and claim that it updates the installed preview.

## Implemented in the Android application
- Settings → Application Updates has a native Check for update / Download & Install verified update flow (with a staged-APK Install retry after permissions).
- Discovers release-tagged assets only in the repository's matching production or preview channel.
- Requires a release APK and matching named .sha256, compares the SHA-256 digest, verifies exact package ID and strictly increasing Android versionCode, then compares the APK's actual signing certificate with the installed application's signer.
- Downloads to a private .part file, performs bounded streaming and atomic staging, and grants Android's system installer temporary read-only access through a non-exported ContentProvider.
- Requests user consent via Android's normal Install unknown apps setting when needed. The installer may require another confirmation.
- Does not silently install, uninstall, migrate or delete application data.
- On next launch, verifies the resulting Android application version and clears outdated staged files.
- Explicit host restrictions reject non-GitHub update redirects, and checksum mismatch or signature mismatch fails closed.
- Regression tests cover semantic version ordering, trusted hosts and exact/conflicting checksum selection.

## Gallery repair retained
- Real source-owned Kuroha/Pixiv preview cards take precedence over unindexed website controls.
- Indexed media and previously collected real Search thumbnails populate their corresponding site/creator cards, without cross-site mixing or fabricated images.
- Bounded thumbnail-only source checks can refresh the gallery without queueing originals or altering backfill cursors.
- Provider-management controls are collapsed for unindexed sources.
- Preview refresh runs independently of ongoing backfill/index jobs, while requests are serialized against other preview refreshes.
- Protected video-thumbnail hosts receive an approved source Referer.

## Verified Windows build
Branch: feature/asterion-unified-downloads-20261030

- Version: **1.3.46-preview**, Android versionCode **75**
- Package ID: **com.azrael.pixivdumpsync.nocturne**
- Build: Gradle 8.13, app:testDebugUnitTest app:testPreviewUnitTest app:assemblePreview
- Result: **BUILD SUCCESSFUL**, 63 actionable tasks
- Locally generated preview APK: C:\Users\mimim\source\repos\AsterionDownloader-Final\app\build\outputs\apk\preview\app-preview.apk
- APK SHA-256: 21779624BBB7B739490C3F3D9858DB7488E1E88D3A940DA228E1184DCAAD64E6
- Local preview signing certificate SHA-256: 9bc439712a0a460cc4703ebf254aaaf9bf3cc6e67b0ced6fc12162ed3ca3b9ad
- Package identity and certificate were verified with Android aapt and apksigner.

## Blocking release and phone acceptance facts
- A published v1.3.41 preview APK previously inspected was signed by **316f4069cb0a87c88379869e9c70adc4a7275dd81ca814fd4e9f1910ea6f06c9**, not the local v1.3.46 signing identity. This is not proof that the inspected v1.3.41 APK is exactly the one installed.
- Historical production Kuroha v0.6.1 has yet another original certificate, documented in docs/ORIGINAL_APK_UPGRADE_SIGNING.md.
- Successful compilation is not proof that the Android package can upgrade the phone's existing installation.
- The v1.3.46 signed-preview workflow failed its mandatory signing-identity gate. GitHub check-run annotation on run 42679749302 explicitly states: Permanent preview signing identity not installed. Refusing to publish another incompatible debug APK. No v1.3.46 release APK was published (release API search found zero). Do not bypass this gate by publishing an ephemeral CI debug certificate.
- Asterion Control's existing phone connection was verified reachable, but Windows ADB device list was empty. No compatible installation, foreground gallery verification or live-provider acceptance was demonstrated.
- Do not mark the visual gallery restored on device solely because source rendering was repaired.

## Required acceptance
1. Read the actually installed Downloader package ID, version and signing certificate on Samsung.
2. Locate the matching signing private key. If unavailable, stop the in-place upgrade and retain user data; no APK-signature bypass exists.
3. Publish the newer APK and its matching .sha256 as same-certificate release assets on the matching channel. Configure guarded CI secrets without committing private signing material.
4. Through the existing Asterion Control link or normal Android installer, perform a confirmed in-place upgrade. Verify installed version, successful launch, Settings updater and retained data.
5. Verify real source-owned thumbnails in Porn/Hentai/Cosplay/creator galleries after indexing; report per-provider errors and never replace failures with fake content.
6. For the subsequent same-signature release, test Settings → Application Updates → Check → Download and verify → Android installer → Installed version confirmed. This is the end-to-end updater acceptance gate.

**Do not claim feature deployed or gallery fixed on Samsung until device acceptance is evidenced.**
