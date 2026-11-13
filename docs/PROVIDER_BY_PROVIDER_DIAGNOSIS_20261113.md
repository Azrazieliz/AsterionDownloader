# Asterion Downloader — evidence-based publisher diagnosis
Date: 2026-11-13. Existing project continuation. Do not treat source or APK tests as publisher acceptance.

## Rule for completion

A provider can be marked ACCEPTED only after a **complete selected original** is saved,
its byte count/expected length verified, and its playable container checked
on the owner's Samsung. Real HTTP 200, yt-dlp metadata, sample ranges,
mini-previews, emulator localhost fixtures, a passing Gradle build, and
a transferred APK do **not** satisfy this.

## Shared failure meanings

- `No verified original found on this page`: originated from
  `AuthenticatedItemDownloader` routing video cards through
  `AuthorizedOriginalResolver`, which scans static image/archive anchors.
  Commit `9de9cda4` now routes individually selected approved ADULT/HENTAI/VIDEOS
  watch pages into VideoFlow. Not proof the downstream site has a player.
- `No working extractor is available`: `yt_dlp` current extractor
  produced Unsupported URL or no suitable extractor. Requires publisher-specific
  player provenance or a new upstream extractor, not Python reinstall.
- `Python extractor ... no video formats`: current player either changed,
  is inaccessible, or returned only a gateway. Distinguish actual source HTTP
  from parser error; commit `8eef8183` preserves 404/429 when observed.
- `HTTP 401/403`: credentials, region, browser challenge, or CDN rejection.
  Need actual authorized source and direct byte proof, never fake source URLs.
- `website could not be reached`: network/timeout or provider rejection;
  inspect original URL, DNS and returned HTTP status separately.
- `Archivebate exposes no public video cards`: homepage may expose an
  18+ Continue shell. Prior real Chrome browser returned 36 numeric /watch/
  records after the normal site Continue action. Parse only those genuine
  watch cards, from the authorized browser; discovery is not the original.

## Site-by-site evidence (not release acceptance)

| Site / selected item | Observed public evidence | Code status | Full file on owner's device |
| --- | --- | --- | --- |
| FreePornVideos Serena, /videos/93823084/... | yt-dlp 2026.10.26 HTML5MediaEmbed produced 3 HTTPS MP4 formats. A selected first-party format without filename extension returned HTTP 206, Content-Range and 32768 valid MP4 bytes. | `9d8bfab3` adds strict first-party metadata→native MP4 handoff; `44590425` tightens host provenance, `b92d4443` rejects unsolicited incomplete ranges. Full local verification separate. | NOT VERIFIED |
| Eporner selected MSADu4C0K92 | Public watch URL HTTP 200, ~3.5 KiB age-verification shell; yt-dlp also reported player hash extraction error. | `08871c4a` detects gate and requires authorized browser source. Cannot substitute the shell as MP4. | NOT VERIFIED |
| Rule34Video selected 4727221 | Bare /video/4727221/ returns HTTP 404 from Windows and GitHub; browser also showed a verification shell. Canonical slugged path unknown. | `8eef8183` preserves observed first-party 404 instead of generic Python failure. | NOT VERIFIED |
| 3DHentaiX NTR Compilation | Selected page HTTP 200; real Chrome exposed short ~30-second player, no public selected full original; yt-dlp Unsupported URL. | Guarded rendered-player resolver rejects teaser. | NOT VERIFIED |
| HentaiBros Deco x Deco | Selected page HTTP 200; short related video clips only; yt-dlp Unsupported URL. | Guarded rendered-player resolver refuses unrelated/short clips. | NOT VERIFIED |
| FullPorner selected recording | First-party /player/9509933/14 was accessible HTTP 200 but exposes browser verification/no public MP4; yt-dlp Unsupported URL. | Access/player requirement documented; no fabricated media URL. | NOT VERIFIED |
| HentaiHaven selected recording | Chrome verification challenge; yt-dlp Unsupported URL. | Connected-browser access required. | NOT VERIFIED |
| UncensoredHentai selected recording | NHPlayer iframe exists; inspected iframe has no verified full HTML5 media. yt-dlp Unsupported URL. | NHPlayer first-party selection guarded. | NOT VERIFIED |
| JAVIDEO sampled FC2 release | yt-dlp Unsupported URL on selected sample; landing HTTP 200 not indicative of file. | Current source admission and media source adapter not proven. | NOT VERIFIED |
| Archivebate | 18+ Continue shell before genuine watch list; public Chrome yielded 36 numeric watch links after Continue. | `AdultProviderListings` parses exact watch links. Account-connected DOM required when source shell remains. | NOT VERIFIED |
| WatchPorn | Source adapter expects independent matching duration fields + genuine /get_file/ MP4. | No device saved-file receipt for selected user item. | NOT VERIFIED |
| Helloporn | Public homepage reachable, no exact selected original/media bytes verified. | Extraction status unresolved. | NOT VERIFIED |
| XXXClub | Public homepage reachable, no exact selected original/media bytes verified. | Extraction status unresolved. | NOT VERIFIED |
| Notfans | Public homepage reachable, no exact selected original/media bytes verified. | Extraction status unresolved. | NOT VERIFIED |
| OnlyHentai | User previously observed successful downloads. | Preserve existing working behavior; don't disturb or retest needlessly. | USER-OBSERVED PASS; latest acceptance not rerun |
| Pornhub | User reported French/SFW regional restriction; VPN exit routing added in `334502c`. | Must verify actual VPN exit and permitted player access. | NOT VERIFIED |
| DHUT / Jav Guru | User reported broken selected media / blank Jav Guru previews. | Jav Guru poster fallback previously patched but full video not verified. | NOT VERIFIED |

## Torrent security / performance

- Previous code exported completed BitTorrent files to public MediaStore Downloads
  solely after piece hash checks — **unsafe**, as those hashes are not AV.
- Commit `bdbc9d2d` prevents public export and retains all payloads in
  app-private storage, records preliminary dangerous-file and archive checks,
  shows connected seeders separately from leechers, and reports no verified
  piece progress/stalled instead of waiting indefinitely.
- Commit `23ce6c9d` exposes the quarantine security review in torrent UI.
- No trusted malware scanner has yet granted any files release approval:
  **quarantine must remain locked**. Previously published files are not
  automatically recalled.
- Connected peers alone do not indicate complete seeders. Even seeders may
  lack the selected pieces or be unreachable; measuring real piece progress is
  required to call the transfer working.

## Release gate

Auto-APK build workflow changed to manual invocation (`715aa6ee`).
Do not publish a test APK as a fix for external provider failures without
selected original saved-file proof. Source/CI fixtures remain separate.
