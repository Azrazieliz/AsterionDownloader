# ASTERION Downloader — Android game execution and cross-platform creator discovery

Date: 2026-11-03. Existing architecture is retained (Kuroha, VideoFlow, Kyora, SourceIndex and Recommendations). This describes implemented paths and clear future decisions, not a promise that proprietary binaries can be decompiled into native Android APKs.

## Game execution: choose the correct result, preserve the original

| Input | Implemented route | Actual output | Additional dependency |
| --- | --- | --- | --- |
| ZIP containing HTML5 game | existing WebView/Gradle packager | independent Android APK | Gradle/Android SDK |
| RPG Maker MV/MZ with complete `www` | existing HTML5 APK packager | independent Android APK | compatible web assets |
| Ren'Py game ZIP with usable `game/` | official RAPT workflow, when SDK available | Android APK | compatible Ren'Py SDK/RAPT |
| Ren'Py distribution without RAPT | `game_runtime_compat.py` | game files + runtime manifest | JoiPlay/Ren'Py plugin |
| RPG Maker XP/VX/VX Ace `rgss*` files | compatibility staging | game files + runtime manifest | JoiPlay/RPG Maker plugin |
| KiriKiri (`xp3`) / NScripter (`nsa`, `ns2`) | compatibility staging | original game files + runtime manifest | matching engine interpreter |
| Godot project with `project.godot` | official Godot Android exporter | Android APK | compatible Godot editor/templates |
| Exported Godot `pck` only | compatibility staging | game files + runtime manifest | matching Godot interpreter; game-specific support |
| Unity project with `Assets` + `ProjectSettings` | existing isolated Unity editor workflow | Android APK, if actual source and activated editor available | Unity Android Build Support |
| Packaged Unity Windows game `exe` + `UnityPlayer.dll` | compatibility staging | Windows game files + runtime manifest | Winlator; compatibility not guaranteed |
| Unreal project with `uproject` | UAT build plan | native Android APK only after a real Android build | Unreal + Android SDK/NDK |
| Packaged Unreal Windows game `exe` + `pak` | compatibility staging | Windows game files + runtime manifest | Winlator; compatibility not guaranteed |
| Other Windows x86/x64 game | compatibility staging | Windows game files + runtime manifest | Winlator or another compatible Windows layer |
| Nintendo DS/3DS | compatibility staging | unchanged game files + runtime manifest | authorized Android emulator |
| Loose engine assets and unknown formats | explicit unsupported/incomplete report | no fabricated APK | actual complete game assets/source |

The Python compatibility workflow writes `compatibility-game/files/` and `compatibility-game/asterion-runtime.json`, preserving original ZIP members after path safety checks. The Android Games module identifies the engine and offers the relevant native build, runtime export, or runtime source. All input game data remains unchanged.

Verified in Windows Python regression: Ren'Py archival staging, RPG Maker RGSS selection, Unity Windows distribution with DLL/assets, and safe ZIP member rejection. Standalone WebView smoke and Android UI tests run in GitHub CI. Real game-by-game runtime compatibility remains unverified until the actual game and runtime are supplied.

**Not supported:** universal binary decompilation/recompilation into an Android-native APK, DRM removal, extracting a complete playable game from unrelated preview/asset files, or native Unity/Unreal Android export without the editable project.

## Cross-platform creator discovery

- Uses profiles actually followed in Pixiv, X, Kyora and creator archives, plus approved creator URLs in SourceIndex.
- Reads explicit social/profile hyperlinks and creator-declared JSON-LD `sameAs`; a matching name never establishes an identity.
- Follows creator-nominated link hubs (Linktree/Beacons/Litlink/Potofu/AllMyLinks/Carrd) with one bounded public hop, then scans evidence-linked destination profiles. Reciprocal links are marked separately.
- Includes documented URL patterns for Japan (Pixiv, FANBOX, Fantia, Ci-en, Skeb, BOOTH, Lofter), China (Weibo, Bilibili, Xiaohongshu), Korea (Naver Blog, Postype), and broad international sites (Patreon, OnlyFans, Fansly, X, Bluesky, ArtStation, etc.).
- All lookups are bounded, cursor-based and retried separately; no third-party cookies are forwarded. Native download engines remain owners of downloads. Public profile access can be blocked by login or JavaScript.
- Recommendations retain individual platform URLs and evidence. A short public preview on one platform is **not** treated as the original full publication elsewhere.

## Concrete high-value next design ideas (NOT implemented by the above)

1. **Runtime setup and compatibility receipts:** detect installed JoiPlay/Winlator versions, present a game-specific profile, save tested runtime settings keyed by SHA-256, and record actual OPEN/ERROR status rather than counting an exported folder as a working game.
2. **Phone-first Godot export:** offer the official Godot Android editor with the GABE Android Build Environment for compatible source projects. This does not convert compiled Windows binaries.
3. **Windows performance profiles:** per-device SoC/GPU presets for Winlator's renderer, box64 configuration, resolution, virtual controls and audio. Use user-tested evidence, not assumed benchmark numbers.
4. **Evidence-ranked creator graph:** distinguish reciprocal profile links, direct outgoing social links, single hub links, and tentative transliterated aliases; group for display only after user confirmation.
5. **Partial/full publication relationship model:** maintain separately indexed Pixiv/X previews, Patreon/FANBOX/Fantia originals and other mirrors, with creator assertions and source-trust metadata; never merge by title or duration alone.
6. **Fallback remote game streaming:** allow existing Windows-only games to be executed on a user-owned PC and viewed on Android if emulation fails. Streaming is explicitly not a conversion.

Official runtime sources: https://www.joiplay.net/ , https://winlator.org/index.php , https://www.renpy.org/ , https://godotengine.org/download/android/ .

## Known hard limits

This repository cannot claim every site in the world has been exhaustively searched. Site-specific login, private posts, dynamic content and deleted profiles remain externally dependent. Network and game compatibility tests must report the exact provider, runtime, device, source version and failure instead of asserting universal completeness.
