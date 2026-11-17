# Accepted video-provider regression freeze — 2026-11-17

## Owner acceptance baseline

Owner reports all video providers except **Jav Guru, JAVIDEO, HelloPorn, Pornhub, HentaiHaven, MP4Hentai and UncensoredHentai** currently working or not failing. RoshyTV is **not** counted as verified: it does not display recognizable transferring/progress, so completion and attribution remain unproven. Existing v1.3.72 APK is preserved; freeze changes are currently **source/test-only**.

## Strict frozen native provider implementations

The following source files are SHA-1 Git blob pinned at the owner-reported v1.3.72 baseline:

| Provider | Frozen dedicated code | Git blob SHA-1 |
| --- | --- | --- |
| Archivebate | `ArchivebateSelectedMedia.kt` | `7380651fe85ba193c6de3ca891622291a7cca6d8` |
| FullPorner | `FullPornerOriginalSource.kt` | `312878511c2b3fbb2be47e571d6cba5de8fbcd67` |
| FullPorner two-range native transfer | `FullPornerParallelOriginal.kt` | `598c7392829a599093b0bd20b1a46ed63051446b` |
| 3DHentaiX | `ThreeDHentaiSignedOriginal.kt` | `9bff43bbdf5d29479e4ac41636b21a2c3b159fa7` |
| Rule34Video | `Rule34VideoOriginalSource.kt` | `02d00276a554a92c685eef249656f9bcaf3e846f` |
| EPORNER | `EpornerOriginalSource.kt` | `c9c4b21720bfbd702d48dea5be6a11dab38bc485` |
| FreePornVideos | `FreePornVideosOriginalSource.kt` | `184e349e1b97800746578f79aa2bc1c9250fdeaa` |

Source freeze is checked in both `WorkingVideoProviderFreezeTest` and the fast Gradle `:app:verifyFrozenVideoProviders` task. Git-for-Windows CRLF checkout is normalized to LF before comparing actual Git blob identities. Modifying a pinned provider requires deliberate baseline update **after** equivalent full Android behavior is demonstrated.

## Shared code behavior regressions

- Hentaibros selected Flowplayer original, never unrelated recommendations
- Rule34Video selected MP4 quality and restored real selected-card poster URL, never loading/default poster
- Existing FullPorner range validation, receipt and resumability tests
- Existing 3DHentaiX signed original/nonce tests
- Existing Archivebate catalog/selected embed tests
- Existing RoshyTV selected player/HLS parser tests **without falsely declaring complete transfer**
- Existing OnlyHentai and other previously working module checks in the full unit suite

Every future `packageAcceptance` task is configured to depend on **both** `:app:verifyFrozenVideoProviders` and `:app:testAcceptanceUnitTest`. This protects shared implementation when repairing the seven failures, rather than freezing entire shared files that must still be changed.

## Pending phone evidence

RoshyTV: source attribution, queue appearance, measured verified-byte delta, final file/receipt and playback. The screenshot shows two active video transfers, **but it does not establish that either belongs to RoshyTV**.

Do not mark any of the seven current failing providers as repaired without a complete selected original verified on Android. No additional APK is created by this freeze.
