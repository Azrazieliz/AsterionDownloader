# Asterion Downloader — signed Android installers

## Latest: v1.3.31-preview (3 November 2026)

- Installable APK: `Asterion-Downloader-v1.3.31-Preview-Update.apk`
- GitHub source commit: `82162cc3b675ef6b2656380ce09506c53eba8346`
- Android package: `com.azrael.pixivdumpsync.nocturne`; version code `60`
- APK SHA-256: `ff0afe2df6b0d662958e48396fa53a727a15109bb0cb6e537966f28e7c0e38a7`
- Certificate SHA-256: `9bc439712a0a460cc4703ebf254aaaf9bf3cc6e67b0ced6fc12162ed3ca3b9ad`
- Matches the signature and package of the earlier v1.3.30 **preview** installed from the PC. It can update that particular preview without uninstalling it.
- GitHub Actions passed source tests and produced the corresponding v1.3.31 APK. This PC copy was re-signed with the existing preview key and verified locally.

**Not an original production Kuroha update:** The original `com.azrael.pixivdumpsync` package has a different identity, and its original signing key is not available in the current GitHub workflow. Do not uninstall the original application or treat this preview as an update to it. Provider access and game-engine exports still depend on provider sessions and appropriate source/toolchains.

## Previously: v1.3.12-preview
- APK: 
Asterion-Downloader-v1.3.12-Preserve-Data.apk
- Package: com.azrael.pixivdumpsync.nocturne
- Version code: 41
- Signing certificate SHA-256: 9bc439712a0a460cc4703ebf254aaaf9bf3cc6e67b0ced6fc12162ed3ca3b9ad
- APK SHA-256: 
109cadb07870963dfa2e6fa1a2660c9389259ba581f14ae1b3088f3183e96532

**Preserve data:** install over the existing preview. Never uninstall it or original production Kuroha.

Previous signed APK versions are retained in this artifact branch.

## v1.3.13-preview � 2026-10-31
- APK: `Asterion-Downloader-v1.3.13-Preserve-Data.apk`
- Package: `com.azrael.pixivdumpsync.nocturne` � version code `42`
- Certificate SHA-256: `9bc439712a0a460cc4703ebf254aaaf9bf3cc6e67b0ced6fc12162ed3ca3b9ad`
- APK SHA-256: `
97ed5e0acb869787f06a1e5c99f8c846a13b4bb29c075a1d6cb1ddab4ead6c3e
`
- CI verified; auto X-following traversal, scoped video search, MangaRead, Toonily browser-retry updates.
- In-place APK update preserves installed Nocturne app data, and original Kuroha is separate.

## v1.3.14-preview � 2026-10-31
- Package `com.azrael.pixivdumpsync.nocturne`, version code 43.
- Signer SHA-256: `9bc439712a0a460cc4703ebf254aaaf9bf3cc6e67b0ced6fc12162ed3ca3b9ad`.
- APK SHA-256: `
f6dcdbeb817cfb093997ad9cbb4a69a3dd71fd7ff4b50a5d1f1218ddcab7d5f0
`.
- Removes mainstream and Tor search, retains adult and saved-site discovery, adds use of already authenticated website sessions.
- Does not bypass external paywalls.


## v1.3.15-preview � 2026-10-31
- Installer: `Asterion-Downloader-v1.3.15-Preserve-Data.apk`
- Package: `com.azrael.pixivdumpsync.nocturne`, version code 44
- Certificate SHA-256: `9bc439712a0a460cc4703ebf254aaaf9bf3cc6e67b0ced6fc12162ed3ca3b9ad`
- APK SHA-256: `
994f55021075173bf6b52e4c5a9affbbd6e321929a6b4ea9ca729fbe8f06094c
`
- Three separate search areas: Hentai, Porn, Piracy / Darkweb; requires actual site accessibility and authorized access.
- Browser-authenticated downloads are supported without bypassing third-party payment or DRM controls.


## v1.3.17-preview � 2026-10-31
- Installer: `Asterion-Downloader-v1.3.17-Preserve-Data.apk`
- Package: `com.azrael.pixivdumpsync.nocturne` � version code `46`
- Signing certificate SHA-256: `9bc439712a0a460cc4703ebf254aaaf9bf3cc6e67b0ced6fc12162ed3ca3b9ad`
- APK SHA-256: `
1804e711e62ac375f237064fe01b9af0bb6af73953ad569ad555e1e3765427ed
`
- Books and games tabs, original X source tile, torrent descriptor/magnet handoff, Hanime search fallback, HTML5 VN ZIP support, external game export.
- External Android runtimes required for engine-specific titles; full Windows-to-Android conversion, embedded BitTorrent peer transfer and third-party DRM/payment bypass not implemented.

