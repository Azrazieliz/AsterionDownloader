# ASTERION DOWNLOADER — APPROVED SOURCE SCOPE (2026-11-01)

## User-approved correction

This is a replacement for the unfiltered Aloha export list, NOT a restart and NOT
permission to remove or weaken existing working app engines.

### Aloha export

Four Aloha exports were identical. Of 76 unique URLs, **43 were approved**.
Only **28** are supplemental sources not already represented by native integrations.
The application must preserve existing Pixiv, Kyora, VideoFlow, Games, Comics and
Assets functionality and not present duplicate bookmarks as new adapters.

* Kyora / image galleries (8): 4KHD, Buondua, CosplayTele, Kiutaku,
  Porn Pics, Archivebate, NotFans, Celeb.st.
* Adult videos (19): CamStreams, FreePornVideos, WatchPorn, Pornhub, EPORNER,
  XVideos, HQPornerxxx, OMG.xxx, Jav Guru, Roshy JAV, JAVIDEO, CUMS,
  PeekVids, PornTrex, TNAFLIX, Pornhits, XNXX, XXXClub, OneJAV.
* Hentai video (7): MP4Hentai, Hentai Bros, OnlyHentai, Uncensored Hentai,
  3DHentai, Hentai Haven, Rule34Video.
* Comics (3): Sushi-Scan, nhentai.xxx and nhentai.net.
* EPUB novels (4): Freewebnovel, NovelBuddy, ReadNovelFull, MP4Directs.
* Game torrent (1): Sukebei Nyaa. Keep the existing non-adult Nyaa engine
  where relevant but DO NOT promote anime-only feeds to game results.
* Assets (1): BepisDB via the existing native Koikatsu card engine.

Excluded from Aloha import: personal/study calendar, game/reference databases,
image editing utilities, general video/anime bookmarks, unrelated websites and
any other bookmark not explicitly approved.

### Extra requested sources (not Aloha bookmarks)

Patreon, FANBOX, Fantia, Gumroad, OnlyFans, Fansly, KoikatsuCards,
Pawchive, Kemono, Coomer, F95Zone, Shionlib, Anna's Archive.

Route each to the **existing relevant system**, never a stand-alone creator
archives tab. A creator-specific historical backfill must start when a creator
is followed; indexing a provider homepage alone cannot be marked as a complete
backfill. Unsupported provider methods must be recorded as unresolved; never
claim a native downloader merely because a website opens.

### Search and download behavior

* Search **all relevant enabled providers by default** for each content type.
  Explicitly selecting one source limits the search to that source only.
* Game results must be actual complete games or game releases, not just
  matching videos; inspect real torrent file lists before queueing. Offer
  multiple candidate titles/releases before the user chooses.
* Books accept **EPUB only**. Chapter ZIPs belong to Toonily/MangaRead.
* Adult performer discovery is across providers, not WatchPorn-only.
* Relevant source backfill should start initially, followed by scheduled
  incremental sync, with original file verification/deduplication and per-source
  errors. Keep persisted user data and allow users to pause sources later.
* All publicly available and legitimately session-accessible originals should
  be downloaded by shared engines where possible. Do not falsely claim
  absent files or DRM-locked resources can be retrieved.
* Preserve working site adapters from pre-merge VideoFlow and Cosplay-Download.
* Prefer privacy: no unnecessary telemetry or third-party transmission
  beyond the sites explicitly queried by the user.

### Regional torrent emphasis

Prioritize **Chinese game indexes** and **Chinese/Korean adult indexes**.
Do not add anime-focused providers as substitutes.

Verified candidate: GGBases (Chinese galgame/magnet index) has a
title-search endpoint and game-specific listings; indexed through
`MultiTorrentSearch.ggbasesListing` and game search.

Additional candidates requiring live source-specific adapter validation:
U3C3 (Chinese adult magnets), 0Magnet (Chinese adult/JAV), and
Torrentsome/Torrenttip (Korean indexers; volatile domains, some observed
to redirect to nonfunctional hosts). Research candidates are not
implemented or verified downloads until the adapter passes a live test.

### Implementation ledger

* COMMITTED: Aloha subset, supplemental deduplication, source-default seed,
  generic on-site video search discovery, GGBases game listing discovery,
  torrent-page handling, native game payload filtering, EPUB-only Books.
* EXISTING: Original VideoFlow/Kyora/Kuroha, creator public-archive search,
  native libtorrent, Koikatsu cards, X photo capture and game ZIP export.
* INCOMPLETE: Native per-site adapters for every new gallery/video/novel;
  complete source backfill/retry for all sites; extra named sources
  F95Zone, Shionlib and Anna's Archive in their content engines;
  verified Korean torrent adapters; full device regression testing.
* Signing/package: NEVER change the existing preview package signing identity,
  uninstall production, clear app data, or overwrite previous signed APKs.
