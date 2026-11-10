# Site acceptance 01 — Eporner (2026-11-10)

**Scope:** One site only. Owner rejected repeated multi-site APK releases without original-download proof. Preserve the approved UI layout and do not claim other sites fixed.

## Grounded live test
- Windows provider official search API: HTTP 200, real video entries with first-party URLs and owned cover URLs.
- Individual video information endpoint: HTTP 200, identity verified and official publisher duration = 15 seconds for the selected test item.
- Public watch page: HTTP 200 but age/verification indicators present, 3,587 bytes; video metadata is not reliably extractable from this response.
- yt-dlp 2026.10.26 against watch URL: failed with `Unable to extract hash`; Chrome HTTP impersonation also failed. This is an upstream/site-player failure, not evidence that a file downloaded.
- First-party official embed endpoint: HTTP 200, 8,083 bytes, extracted one actual HTTPS MP4 source at 240p, with 15s metadata.
- **Actual complete 15-second original video transferred** using the official embed source. Saved bytes: **989,365**; verified MP4 `ftyp` signature; test media was deleted immediately afterwards.
- This proves Windows end-to-end transfer for one available original. It is NOT yet Samsung/Android acceptance or confirmation of every Eporner video/quality.

## Implemented in source
- `AsterionAdultHub.kt`: a clicked provider-owned video preview now queues the exact original video URL instead of starting an unrelated site-wide Backfill; the UI card structure was unchanged.
- `EpornerOriginalSource.kt`: validates exact first-party video IDs, routes to the official embed first only after an explicitly connected site session, verifies the original's independently published runtime using `/api/v2/video/id/`, and rejects cross-site/lookalike URLs.
- `TransferService.kt`: uses these site-specific candidates and official runtime evidence rather than waiting on a known broken watch-page extractor first. All other providers remain on existing transfer logic.
- `FullLengthVideoPolicy.kt`: permits a genuinely complete shorter published video only when its full extracted runtime matches the independent first-party runtime; nonverified short videos and trailers remain rejected.
- `VideoExtractorErrors.kt` and `VideoFailurePolicy.kt`: the observed hash failure is classified as actionable instead of misleading completion or unbounded retry.
- `EpornerOriginalSourceTest.kt`: confirms verified candidate ordering, source identity restrictions, session gate, short original runtime acceptance and trailer rejection.
- Regression build: Windows `:app:testPilotUnitTest :app:assemblePilot` **BUILD SUCCESSFUL**, 41 tasks, source revision `e63dc63d98d0b3c254bf7fd8e5e4df8ce97059fb`.
- Locally built Pilot APK SHA256 `48F16D0952026FA2DC387979CF39391D5B8C42738048E85A0467F84FDE374313`. Not published as another nominally fully-fixed APK.

## Important acceptance caveats
- **Samsung end-to-end original transfer: NOT VERIFIED.** Android via Asterion Control was actively showing another app, and Windows ADB had no authorized device. Do not interrupt that activity to assert success.
- Downloading still requires the user's authorized website session if the provider requests age/login verification. Never bypass mandatory verification or forward cookies to an unrelated host.
- A real first-party catalog thumbnail is not a downloaded original.
- Earlier pilots used *different package IDs* and debug signers. An APK published under a new package does not update the existing installed app. Do not claim visual or functional updates were installed unless package, signer and on-device version have been confirmed.
- The approved card layout is frozen; the thumbnail click handler changed behavior, not appearance. CI UI immutability gate must permit precisely this validated behavioral exception without allowing a layout rewrite.
- Do not mark this site fully accepted until an actual Samsung in-app queued original reaches a verified file receipt.

## Next gating step
Acceptance of this single site on an authorized Android device, with exact signed install identity; only then proceed to the next failing provider without bundling unverified site changes into successive releases.
