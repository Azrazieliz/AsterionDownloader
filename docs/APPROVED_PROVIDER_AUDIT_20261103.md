# Asterion Downloader — approved-provider public-surface audit (2026-11-03)

**Scope:** Read-only public HTML sampling of all 43 approved Aloha sources and 13 additional platform roots. No account credentials, paid media, original payloads or download backfill were involved. **HTTP success or a detected preview is NOT a verified full-history/backfill adapter.**

Sample: first 192 KiB of HTML per URL; max 9 seconds per source; concurrent 10 workers. Host reachability varies by geography, cookies, bot detection and time.

## Summary

- PUBLIC_LISTING: **33**
- PUBLIC_INDEX: **4**
- NETWORK_ERROR: **6**
- NO_LINKS: **9**
- AUTH_OR_BLOCK: **4**

## Source-level evidence

| Source | Content | Probe result | HTTP | Media hints | Paging hints |
|---|---|---|---:|---:|---:|
| 4KHD | COSPLAY | PUBLIC_LISTING | 200 | 0 | 0 |
| Buondua | COSPLAY | PUBLIC_LISTING | 200 | 0 | 2 |
| CosplayTele | COSPLAY | PUBLIC_LISTING | 200 | 0 | 1 |
| Kiutaku | COSPLAY | PUBLIC_LISTING | 200 | 0 | 2 |
| Porn Pics | COSPLAY | PUBLIC_LISTING | 200 | 0 | 0 |
| Archivebate | COSPLAY | PUBLIC_LISTING | 200 | 0 | 0 |
| NotFans | COSPLAY | PUBLIC_INDEX | 200 | 1 | 0 |
| CamStreams | ADULT | PUBLIC_INDEX | 200 | 1 | 0 |
| Celeb.st | COSPLAY | NETWORK_ERROR | — | 0 | 0 |
| FreePornVideos | ADULT | PUBLIC_LISTING | 200 | 0 | 0 |
| WatchPorn | ADULT | NETWORK_ERROR | — | 0 | 0 |
| Pornhub | ADULT | NO_LINKS | 200 | 0 | 0 |
| EPORNER | ADULT | PUBLIC_LISTING | 200 | 0 | 0 |
| XVIDEOS | ADULT | PUBLIC_LISTING | 200 | 0 | 1 |
| HQPornerxxx | ADULT | PUBLIC_LISTING | 200 | 0 | 0 |
| OMG.xxx | ADULT | PUBLIC_LISTING | 200 | 0 | 0 |
| Jav Guru | ADULT | PUBLIC_LISTING | 200 | 0 | 1 |
| Roshy JAV | ADULT | PUBLIC_INDEX | 200 | 34 | 0 |
| JAVIDEO | ADULT | PUBLIC_LISTING | 200 | 0 | 0 |
| CUMS | ADULT | AUTH_OR_BLOCK | 403 | 0 | 0 |
| PeekVids | ADULT | PUBLIC_LISTING | 200 | 0 | 1 |
| PornTrex | ADULT | AUTH_OR_BLOCK | 403 | 0 | 0 |
| TNAFLIX | ADULT | PUBLIC_INDEX | 200 | 2 | 0 |
| Pornhits | ADULT | NO_LINKS | 200 | 0 | 0 |
| XNXX | ADULT | PUBLIC_LISTING | 200 | 0 | 0 |
| XXXClub | ADULT | PUBLIC_LISTING | 200 | 0 | 0 |
| OneJAV | ADULT | AUTH_OR_BLOCK | 403 | 0 | 0 |
| MP4Hentai | HENTAI | PUBLIC_LISTING | 200 | 0 | 1 |
| Hentai Bros | HENTAI | PUBLIC_LISTING | 200 | 0 | 0 |
| OnlyHentai | HENTAI | PUBLIC_LISTING | 200 | 0 | 0 |
| Uncensored Hentai | HENTAI | PUBLIC_LISTING | 200 | 0 | 1 |
| 3DHentai | HENTAI | PUBLIC_LISTING | 200 | 0 | 0 |
| Hentai Haven | HENTAI | PUBLIC_LISTING | 200 | 0 | 0 |
| Rule34 videos | HENTAI | NETWORK_ERROR | 200 | 0 | 0 |
| Sushi-Scan | COMICS | NO_LINKS | 200 | 0 | 0 |
| Freeweb Novel | BOOKS | PUBLIC_LISTING | 200 | 0 | 0 |
| NovelBuddy | BOOKS | PUBLIC_LISTING | 200 | 0 | 0 |
| ReadNovelFull | BOOKS | PUBLIC_LISTING | 200 | 0 | 0 |
| NHentai mirror | COMICS | PUBLIC_LISTING | 200 | 0 | 0 |
| Sukebei Nyaa | GAMES | NO_LINKS | 200 | 0 | 0 |
| MP4Directs light novels | BOOKS | PUBLIC_LISTING | 200 | 0 | 0 |
| NHentai | COMICS | NETWORK_ERROR | — | 0 | 0 |
| BepisDB Koikatsu | ASSETS | NO_LINKS | 200 | 0 | 0 |
| Patreon | CREATORS | AUTH_OR_BLOCK | 403 | 0 | 0 |
| FANBOX | CREATORS | NO_LINKS | 200 | 0 | 0 |
| Fantia | CREATORS | PUBLIC_LISTING | 200 | 0 | 0 |
| Gumroad | ASSETS | PUBLIC_LISTING | 200 | 0 | 0 |
| OnlyFans | CREATORS | NO_LINKS | 200 | 0 | 0 |
| Fansly | CREATORS | NO_LINKS | 200 | 0 | 0 |
| KoikatsuCards | ASSETS | PUBLIC_LISTING | 200 | 0 | 0 |
| Pawchive | CREATORS | PUBLIC_LISTING | 200 | 0 | 0 |
| Kemono | CREATORS | NETWORK_ERROR | — | 0 | 0 |
| Coomer | CREATORS | NO_LINKS | 200 | 0 | 0 |
| F95Zone | GAMES | PUBLIC_LISTING | 200 | 0 | 0 |
| Shionlib | BOOKS | PUBLIC_LISTING | 200 | 0 | 0 |
| Anna's Archive | BOOKS | NETWORK_ERROR | — | 0 | 0 |

## Required distinction

- `PUBLIC_LISTING`: valid HTML with ordinary links, not proof of original downloadable media.
- `PUBLIC_INDEX`: at least one media-like hyperlink in the sampled HTML; not proof of accessible original files, backfill or a working adapter.
- `NO_LINKS`: little usable HTML from this sampling window (often script-rendered).
- `AUTH_OR_BLOCK`: HTTP 401/402/403/429 access or rate-limit response, not a download-success state.
- `NETWORK_ERROR`: timeout or network exception, without proof that the provider is down.

## Next steps

1. Test real profile/gallery URLs, including pagination and original-file extraction, with appropriately authorized accounts.
2. Record actual latest-item count, reachable historical pages, source-specific retry rules and cursor completion for each supported provider.
3. Preserve the originals of Kuroha, Kyora, VideoFlow and Koikatsu adapters; do not replace a native adapter with generic HTML.
4. Keep failed providers visible with their exact, sanitized error state rather than marking backfill complete.

This document is an evidence checkpoint, not a completion certificate.
