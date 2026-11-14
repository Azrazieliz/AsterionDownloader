# Asterion Downloader — provider and EPUB acceptance (2026-11-11)

Continuation of existing app on branch `work/downloader-site-adapters-errors-20261110`. The approved navigation and visual design must remain unchanged. The user confirmed Toonily and MangaRead comic ZIP transfers work; do not regress or replace their native engines.

## Work implemented

- **Pornhits retired**: removed from approved Aloha catalog and VideoFlow route registration. Legacy SourceIndex/VideoDb watches and pending transfers are disabled, not deleted. Existing completed originals and historical records remain untouched. Previously added custom enrollment is rejected. `ProviderRetirementTest`, Aloha catalog tests and video routes updated.
- **FreeWebNovel EPUB**: no fake "success" before the book is complete. The reader now persists per-chapter metadata/cursor and completed chapter text in existing private cache, executes bounded slices of up to 64 chapters or 100 seconds, schedules delayed continuation, checks the observed chapter total, and generates final EPUB using a streaming ZIP writer to avoid a whole-book heap allocation. Existing CHECK NEW can recover paused/retry/error jobs without UI change. Separate two-book workers retained.
- **HentaiBros**: removed duplicate `VideoSiteProfiles` entry where `associateBy` discarded native detail slug recognition. Authentic `/title-episode-2/` and `/watch/...` URL patterns now share a single provider rule. Exclude known category and account pages. Recognition does not guarantee extraction.
- **OMG**: support numeric detail URL paths with optional language prefix; previous domain-alias handling for `omg.xxx` to `omg.porn` retained. Recognition does not guarantee extraction.

## Individual provider public-response checks (not Samsung testing)

| Source | Public listing/detail | Actual original transferred on phone? |
| --- | --- | --- |
| OMG.porn | External public safety scan found `omg.porn -> www.omg.porn`; direct site visit unavailable through this web access | **No evidence** |
| HentaiBros | Home and real `/muramata-san-no-aijou-episode-2/` detail accessible; reader advertises JavaScript player | **No evidence** |
| CosplayTele | Home and `/scheherazade/` detail accessible; source page points at Mediafire, Telegram, SoraFolder and Gofile external archive services | **No evidence; explicit cloud archive resolver needed** |
| Kiutaku | Home and `/7428` detail accessible | **No evidence** |
| 4KHD | Home and `/content/10/october-2026-fantia.html` detail accessible | **No evidence** |
| Buondua | Home accessible | **No evidence** |
| PornPics | Home accessible, login elements visible | **No evidence** |
| Archivebate | Home accessible but age-gate landing returned | **No evidence; authentic account/session flow** |
| NotFans | Public fetch timed out | **No evidence** |
| Celeb.st | Requested `/feed/hot` path not accessible by web tool | **No evidence** |

Do not treat public site reachability, a thumbnail, or an indexed preview as evidence that the original was downloaded or saved. No phone-control connector was exposed in this ChatGPT session, so no Samsung Android runtime, simultaneous transfer, receipt, installer or crash acceptance is claimed. The latest source commit must still pass Android CI and then be tested on the original phone, per source, before release acceptance.

## Required device receipts

For each tested source record: installed app package/version/signing identity; date and exact media detail page; listing discovered; provider/redirect chain; direct original request; byte-progress; final Content URI; MIME/content signature and expected-size validation; Android failure/cancellation/retry code. For EPUB use a real FreeWebNovel series URL, visible chapter count, cursor advancement across job restarts, complete EPUB integrity, opening in a reader and preservation of source chapters. For two-worker acceptance, show two different simultaneous byte-progress curves and two saved original receipts. For crashes capture the uncaught-exception report; crashes during gallery/transfer must not be inferred fixed from unit tests alone.
