# ASTERION Downloader — v1.3.39 repair report

## Changes on the existing feature branch

- Corrected source matching: compare canonical full URLs first, then select a unique same-host source only if unambiguous. Avoid routing a registered source to the wrong scanner.
- Manual Backfill and automatic Sync are independent. The initial toggle for an unregistered site is off; switching Sync on registers an actual native watcher.
- Manual Backfill is a command, not an always-on toggle. Each site has an independent Stop action; queued jobs can also be stopped.
- Historical page cursors remain in the existing source databases. Incomplete manual Backfill requests are persisted and resumed by Android JobScheduler after bounded passes, app restarts and worker interruptions.
- Source state distinguishes RUNNING, STOPPING, QUEUED, INTERRUPTED, STOPPED, ERROR and CHECKED. CHECKED is only recorded when the scanner confirms its historical cursor is complete.
- Existing improvements in v1.3.38 remain: video transfer-first scheduling, per-video/X-image/comic ZIP pause-resume, provider-specific media filtering, native torrent release selection, MP4Directs redirect and source controls, and unified Search/Add.
- Added regression tests for URL-vs-host source identity and creator-collision handling.

## Compatibility and evidence

- Android preview package: com.azrael.pixivdumpsync.nocturne (separate from production).
- Version code: 68; version name: 1.3.39-preview.
- Kotlin compilation and both debug and preview unit test suites passed on Windows.
- Android emulator launch smoke test is enabled in GitHub Actions for this branch.
- No Android phone was visible to ADB during this patch. Native device workflows and live provider-specific download completion cannot be claimed verified.
- A provider that responds with inaccessible files, unsupported parsing, 403 or 429 may remain unresolved. The scanner reports the error instead of marking its content downloaded.
- No user databases, source files or historical media were deleted or reset by this patch.
