# Kuroha

For the current Asterion Downloader staging branch, see the [14 November 2026 repair and acceptance handoff](docs/production/DOWNLOADER_STAGING_HANDOFF_2026-11-14.md) and the [adult live and full-video provider shortlist](docs/production/DOWNLOADER_ADULT_PROVIDER_SHORTLIST_2026-11-14.md).

Kuroha is an Android-first private Pixiv archiver for followed and explicitly watched artists.

## Current behavior

- In-app Pixiv WebView login; session credentials stay on-device.
- **Following Feed Live Sync** archives newest illustrations from accounts followed by the connected Pixiv account.
- **Watched Artists** provide per-artist Live toggles, selection, archive status, profile picture, display name and a strip of latest artworks.
- **Import Pixiv follows** can populate the watched-artist list from the connected account.
- **Archive Backfill** downloads historical works for selected watched artists.
- Automatic background Live checks use Android JobScheduler.
- Live checks that arrive while another sync is active are coalesced into one pending global Live pass.
- Backfill yields to queued Live checks and resumes afterward.
- Pause / Resume / Stop are cooperative and preserve completed work.
- Multi-page illustrations are saved page by page.
- Ugoira are currently skipped.
- A work is bookmarked on Pixiv only after every expected image page exists locally.
- Existing Pixiv bookmarks do not block re-downloading missing local files.
- Files are stored in **Downloads/Kuroha/**.
- Legacy **Downloads/PixiFlow/** files are recognized so an app update does not force a redownload.

## Identity

Application ID remains `com.azrael.pixivdumpsync` for update compatibility.

Current version: **0.5.1**.

## Kuroha v0.7.0 — tag exclusions and TeraBox API backup

### Block artworks before downloading
Open **Download Filters → Edit excluded tags** and enter one Pixiv tag per line.
Rules use exact, case-insensitive, Unicode-normalized matching against the raw
Pixiv tag and any English translation returned by its artwork API. Live,
Backfill and direct-download paths use the same pre-download check. A blocked
work is saved as `SKIPPED_TAG` (and counted as skipped for archive progress).
**No pages are downloaded, no Pixiv bookmark is added, and existing local
files are never deleted.** Removing a rule lets a later Backfill reconsider
the work. Already archived works are not retroactively removed.

### TeraBox Open Platform backup
Open **TeraBox Cloud Backup → TeraBox settings**. This integration uses the
[official Open Platform](https://www.terabox.com/integrations/docs/chinese)
rather than the unrelated teraabox.com link-shortener API or NDUS web-cookie
endpoints.

**Prerequisites:** An approved Open Platform application, a valid authorized
OAuth access token, and its exact app-assigned remote sandbox folder
(`/From: Other Applications/<assigned-app-folder>`). Approval / credential
issuance must be completed with TeraBox. The Android client deliberately
does not embed a client secret. For now, access-token refresh and initial OAuth
login require an external approved-app authorization flow; expired tokens must
be replaced in settings. Do **not** enter a TeraBox password.

The token is encrypted by Android Keystore and cloud sync is disabled by
default. After each local artwork page is safely written, the page is queued
for cloud upload. The standard sync also drains part of the queue; the
**Upload existing / pending** button queues the existing archive and runs a
foreground cloud-only upload session. Upload failures are retried later with
backoff and do not break the local archive or Pixiv bookmark semantics.

The upload implements: `POST /oauth/tokeninfo` to resolve region-specific
API/upload hosts; `precreate`; 8 MiB-target multipart
`/rest/2.0/pcs/superfile2` chunk uploads (MD5-verified);
`create` and final size / file-ID verification. Requests use HTTPS and
reject redirecting uploads to arbitrary hosts. Cloud completion is recorded
in a separate durable SQLite outbox. Cloud uploads preserve local files;
cloud-only mode is not implemented. Actual TeraBox upload verification
requires approved working API credentials and a live account.

### Important limitations
- This is a direct Android-to-TeraBox API upload, **not server-to-server Pixiv→TeraBox copy**.
- Local downloaded files are retained. Clearing a blocklist does not
  automatically resurrect already-baselined Following feed items; use Backfill.
- Pixiv Ugoira remain skipped as before.
- OAuth login/refresh automation, folder discovery and production cloud
  integration tests are gated on approved TeraBox partner credentials.

## Asterion Downloader v1.2.0 — VideoFlow fusion

The Android home presents Kuroha (Pixiv), Kyora (cosplay) and VideoFlow
(video URL download and monitored source pages) in a single APK.
VideoFlow persists its own queue and videos under Downloads/VideoFlow/;
Pixiv/Kyora auth, databases and filters are retained.

## Asterion Downloader v1.2.1 — unified visual identity

Launcher icon and in-app brand mark use the same Asterion A/download insignia,
with preserved dark slate, mint and violet visual language. The hub and Kyora,
Kuroha and VideoFlow header/notifications display the Asterion Downloader
parent identity while retaining each workspace name. The preview launcher name
is `Asterion Downloader Preview`; app and archive package paths, private data,
and signing configurations are deliberately unchanged.
