# Asterion Downloader v1.3.73 Acceptance — signed phone transfer

Date: 2026-11-17

## Verifiable delivery

- Source head: `cdf6cf149107b820479164d399fed7c7e33c78c0`, branch `repair/downloads-nine-providers-backfill-diagnostics-20261116`.
- Android package: `com.azrael.pixivdumpsync.acceptance` (same package as installed owner Acceptance app).
- Version: `1.3.73-acceptance`; versionCode `102`.
- Keystore and APK certificate checked against retained owner SHA256: `8914af1b2713e1d7e93c52abfddf11cbc6bba8d7057906161185a4216dcc0c19`.
- Full `:app:assembleAcceptance --offline`, including mandatory `verifyFrozenVideoProviders` and `testAcceptanceUnitTest`: `BUILD SUCCESSFUL in 2m 50s`, 44 actionable tasks; 13 executed, 31 up to date.
- Signed file: `C:\Users\mimim\Downloads\Asterion-Downloader-v1.3.73-Acceptance.apk`.
- APK size: **54,412,225 bytes**.
- APK SHA256: `233fe31d958ca439c37e6387903ab0e05224826530240e817bdf37b081d716ea`.
- Native Asterion Control transfer job: `cf733765b2f844d69b6addc17ee78e08`, returned `transferred_verified_not_installed` with matching exact APK size and SHA256 on phone.
- On-phone path: `Downloads/Asterion Control/Asterion-Downloader-v1.3.73-Acceptance.apk`.
- **Not installed by automation.** This protects user foreground state and avoids unrequested interruption. The user can update through My Files > Internal storage > Download > Asterion Control; tap APK > Update. Do not uninstall.

## Included candidate repairs since v1.3.72

- RoshyTV and other multitrack yt-dlp HLS transfers: conservative progress based on slowest complete fragment checkpoint, never pretend byte percentage when denominator absent.
- Jav Guru / Pornhub: preserve active `.part-FragN` originals across extractor fallback; mobile Pornhub watch prewarm; selected legitimate video-host cookies.
- JAVIDEO: guarded exact first-party `play.javideo.net` video.js player activation; live selected iframe and request diagnostics.
- HelloPorn VOE and UncensoredHentai NHPlayer: strict exact-frame recognition, browser activation and scoped owner-authorized player session.
- MP4Hentai UpFiles: retry exact selected hoster page after the user completes normal verification in Connect Site. No CAPTCHA bypass or false media success.
- Existing working video providers: eight frozen implementations and relevant selected original/poster fixture checks; packaging requires full acceptance unit suite.

## On-phone acceptance, not yet verified

Testing actual selected full media on Android is needed for Jav Guru, JAVIDEO, HelloPorn, Pornhub, HentaiHaven, MP4Hentai, UncensoredHentai. The owner confirmed RoshyTV was receiving original bytes; full playable file and new percentage display remain to be assessed. Unit/build success and verified APK transfer are **not proof of live provider downloads**.
