# Kuroha → Asterion Downloader Pixiv deduplication (11 November 2026)

## Canonical product decision
Kuroha `com.azrael.pixivdumpsync` is an **existing separate app/artifact** and must not be overwritten or uninstalled. The new Downloader test app has a distinct Android application ID. Its internal SQLite database is not the same as Kuroha's private `pixivdump.db`; Android prohibits reading the other app's private database without an explicit authorized export. Never claim the database itself was copied.

## Implemented read-only route
- In Downloader → Settings → Kuroha / Existing Pixiv Downloads, select the saved-images subfolder `Downloads/Kuroha` (optionally `Downloads/PixiFlow`) using Android's Storage Access Framework picker. Do not select the restricted top-level Downloads directory.
- `LegacyPixivInventory.kt` obtains read-only persisted URI access and indexes only authentic per-page Pixiv filenames `<illust_id>_p<index>.<image_extension>` into its private SQLite index. Kuroha files and data are unchanged. Indexing is transactional, bounded, and errors are reported.
- `SyncEngine.kt` checks Kuroha's accessible, nonempty originals at every page before download, regardless of extension; partial works only download missing pages. No legacy-only file is pushed into the new app's cloud upload queue.
- If a separate Kuroha app is detected and its archive folder has not been linked, the new Pixiv sync waits instead of mass-downloading everything. If a linked folder becomes unreadable or its initial scan is running, it waits instead of treating the archive as empty.
- The existing Android MediaStore scan opportunistically imports visible saved images without requesting broad storage access; on modern Android, this does not substitute for owner-authorized SAF access.
- This is a **physical original dedup** index; private old `works`/bookmark and remotely moved files are NOT migrated. The latter require an explicit Kuroha data-export route. Do not fabricate those records.

## Acceptance: do not conflate
- Build/JUnit success: see GitHub Actions on `work/downloader-site-adapters-errors-20261110`.
- Android phone acceptance required: link a folder containing at least one true Kuroha Pixiv original, check import count and URI readability, run sync of known multi-page art, confirm 0 duplicate HTTP bytes for imported pages and only missing pages transfer, confirm persisted permission after process restart.
- Also required separately: two simultaneous **increasing byte counters** per module (not two queued entries), persistent retries and 403 sign-in path, successful full original receipts, verified EPUB/comic downloads, no app/process crashes.
- Native Asterion Control Samsung access and signed build identity are separate from build success; record actual device receipts before marking these passed.
