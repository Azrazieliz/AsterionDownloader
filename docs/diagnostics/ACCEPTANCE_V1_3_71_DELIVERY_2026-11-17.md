# Asterion Downloader v1.3.71 Acceptance — owner delivery and phone test gate

## Verified build / transfer (2026-11-17)
- Source branch: `repair/downloads-nine-providers-backfill-diagnostics-20261116`
- Built source head: `70895f9367b1700a98b8121f118cb9b2ca68c627` (includes this build script; this checkpoint document is later)
- Version: `1.3.71-acceptance` (code 100)
- Package: `com.azrael.pixivdumpsync.acceptance`; install over code 99, **never uninstall or clear app data**
- Windows retained signer: `D:\AsterionControlRelease\signing\downloader-acceptance\asterion-downloader-acceptance.p12`; DPAPI secret remains private on Windows
- Verified APK signing fingerprint: `8914af1b2713e1d7e93c52abfddf11cbc6bba8d7057906161185a4216dcc0c19`, matching existing v1.3.70 acceptance APK
- Offline `:app:testAcceptanceUnitTest`: **BUILD SUCCESSFUL**, exit 0, bridge command `dl-update-offline-tests-20261117-gpt6-a4`
- Signed `:app:assembleAcceptance`: **BUILD SUCCESSFUL**, exit 0, `dl-update-signed-build-short-20261117-gpt6-b3`; package and version verified from APK
- Signed APK: `C:\Users\mimim\Downloads\Asterion-Downloader-v1.3.71-Acceptance.apk`, 54,385,741 bytes
- SHA-256: `c1da9ad536e45ff8a5997702bf8787f55c3e9a4ba69fcf4544d53da7815a0300`
- Native Asterion Control transfer: **COMPLETE**, receiver SHA-256 match, bridge command `dl-update-transfer-phone-20261117-gpt6-c1`, transfer ID `81c0f6f95bfc42ae9f3919b97d14daf9`
- Phone file location: `Downloads/Asterion Control/Asterion-Downloader-v1.3.71-Acceptance.apk`
- **Phone installation: NOT VERIFIED/NOT PERFORMED**. Latest read-only `phone_ui` showed ChatGPT foreground with an active nonempty draft. To avoid losing the owner's draft, no app switch/installer interaction was issued. Android package install may require owner approval.

## First owner test pass (after installing over existing package)
1. Confirm **v1.3.71-acceptance code 100** and existing queues, sessions and downloaded history are retained; close/reopen app and check crash.
2. **Jav Guru:** select the genuine post, initiate original HLS transfer, verify nonzero actual bytes, full saved playable original and correct scene. PC Kotlin selected HLS source live-tested; Android full transfer remains unverified.
3. **Pornhub:** select a genuine accessible watch; expect selected quality HLS, nonzero fragment progress and correct completed media. The source parser was changed for `hm-h.phncdn.com`; offline regression passes, live Android remains unverified.
4. **FullPorner:** select one short/genuine ~93.6 MB publisher source first; confirm complete file, full playback, source identity and resume. The prior 400 MB case had stalls/503.
5. **Queue/concurrency:** enqueue two independent selected originals; both must genuinely advance (with available phone RAM), then pause/resume one, discard one, and check no stuck queued/done duplicates.
6. **Hentaibros / 3DHentaiX:** confirm the completed source matches the selected work, not a preview/recommendation. 3DHentaiX 452MB and Roshy TV 762MB PC originals exist but Android receipt remains unverified.
7. **Regression smoke:** Toonily and MangaRead ZIP still work; Rule34Video posters show; Pixiv sync/backfill and X Following unchanged; FreeWebNovel 14-chapter EPUB if supported by UI. Do not conflate PC partial EPUB with a full novel.
8. **Known expected remaining failures to report without false pass:** JAVIDEO selected Streamtape, HelloPorn VOE connection, HentaiHaven verification, UncensoredHentai NHPlayer, MP4Hentai hoster/captcha; Archivebate 2.07GB source is only range-proven.

For any failure record selected provider/post, app screenshot/error, queue status, saved actual byte count and whether the file survives restart. No provider is Android-certified solely by JVM tests or PC byte ranges.

## Safety
No original application uninstall, data wipe, signer replacement or unrelated app rebuild. Android installation and on-device acceptance are pending explicit observable result.
