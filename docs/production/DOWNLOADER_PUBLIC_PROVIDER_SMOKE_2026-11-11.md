# Asterion Downloader — public provider and Android acceptance smoke tests (2026-11-11)

## Evidence status / limitations

The latest source branch HEAD checked during this test cycle was `b3189cea324ee65df64085c10856e0529acdc6b2`. CI run `43016398532` completed successfully (unit-test suites, pilot/acceptance APK build, package/signature checks). **CI does not demonstrate a completed source download.**

The existing owner-authorized Asterion Control GitHub bridge was successfully used earlier in this conversation for the installed Android Acceptance build, including source-page UI states and Downloads. The new phone-status and UI probes `dn1111ui1347` and `dn1111status1347` were issued in `Azrazieliz/Asterion-Control-Bridge` but had **no execution receipts when checked**; do not claim a fresh device test or background execution.

## Source-by-source publicly browsed pages, 2026-11-11

| Website | Public check | Consequence / unresolved |
| --- | --- | --- |
| https://hentaibros.net/ | 30 separate numbered-duration home-page entries accessible; https://hentaibros.net/muramata-san-no-aijou-episode-2/ exists as an individual page and shows a JavaScript-required player | New listing selector and cover adapter have local regression tests; original stream extraction and saved file are NOT proven |
| https://javideo.net/en/ | Catalog with individual movie pages and first-party image URLs accessible, including https://javideo.net/en/fc2ppv-5006263 ; its runtime field can be blank and player provides several server choices | Catalog entries ≠ verified original. Placeholder-cover rejection is correct; runtime/source selection still needs device check |
| https://www.porntrex.com/ | Public catalog accessible to independent browsing client | Earlier Windows/Android workers encountered 403/redirect; client-specific access remains unresolved |
| https://www.eporner.com/ | Public video catalog accessible | Error previously reported for listing thumbnails despite indexed videos; verify updated adapter on device |
| https://jav.guru/ | Public individual article listings accessible | Android native HTTP client previously returned 403, so authorized-session route is still necessary; do not claim original bytes |
| https://onejav.com/ | Independent browsing client returned HTTP 403 | Account/session/access blocker, not proof of a missing parser |
| https://www.xnxx.com/ | Independent browsing client unable to open | Unresolved client-specific access |
| https://www.cums.net/ | Independent browsing client unable to open | Unresolved client-specific access |
| https://archivebate.com/ | Public page presents an entry/age-gate and navigation but no usable archive gallery entries in the static response | A gallery thumbnail absence on that page is genuine; an authorized rendered session may expose gallery data |
| https://celeb.st/feed/hot | Independent browsing client cannot open | Android's cached covers are not proof of public extractability; original downloads unverified |
| https://www.pornpics.com/ | Public response primarily category navigation | Rejecting category tiles as single original galleries is required |
| https://www.onlyhentaistuff.com/ | Public video listing exposed | Listings alone do not demonstrate original file retrieval |

## FreeWebNovel exact reproduction

- https://freewebnovel.com/novel/dual-cultivation-novel exposes chapter-list links and displayed chapter numbers.
- https://freewebnovel.com/novel/dual-cultivation-novel/chapter-1 exposes a browser title naming Chapter 1, body content, and a real next-chapter link.
- Installed old Acceptance build showed `Reader chapter number not visible`, 0 chapters cached, failed EPUB.
- Source parser now reads numbered chapter titles from visible headers, metadata or HTML title, with a regression test. **Full EPUB still not proven on the Samsung**, and the original unmodified installed app is not updated by GitHub CI.

## Previous Samsung observations (same project continuation)

- Porn module showed 19 sites, 7 source errors; HTTP 403 at CUMS, Jav Guru, OneJAV and XNXX; PornTrex redirect; JAVIDEO unsupported listing; EPORNER cover error.
- HentaiBros showed 30 indexed records but no preview covers.
- Cosplay Archivebate no verified gallery previews; Celeb.st listing preview error; Porn Pics generic category tiles.
- Downloads had **0 active, 2 failed, 1 saved ZIP**. No live concurrent download proof or paired verified receipts.

## Acceptance left

1. Install/check the newest owner-signed APK with data preservation or use a clearly separate test application.
2. For each site above, record separate (a) listing result, (b) real media click, (c) queued/downloading byte progression, (d) valid completed original, (e) errors and retry recovery.
3. Prove two simultaneous *different* downloads with monotonically increasing byte counts and two valid independent file receipts. Queued jobs and multiple worker objects do not suffice.
4. Soak-test Koikatsu full-aspect previews, native browsing, pause/resume styling, scroll performance and crashes.
5. Do not use an independent web client's success to claim the on-device app can download the original; provider traffic is often different by user-agent, region, session and player.
