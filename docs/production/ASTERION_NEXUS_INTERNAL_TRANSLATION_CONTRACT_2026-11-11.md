# ASTERION NEXUS — EMBEDDED OFFLINE BOOK / COMIC TRANSLATION CONTRACT

Date: 2026-11-11  
Status: **REQUIREMENTS LOCKED — IMPLEMENTATION NOT CLAIMED**  
Canonical product: **Asterion Nexus**; host repository: **Azrazieliz/AsterionDownloader**  
Parent: [ASTERION_NEXUS_MERGE_SCOPE_2026-11-08.md](ASTERION_NEXUS_MERGE_SCOPE_2026-11-08.md)

This contract uses the latest owner-defined automatic-translation scope: **CJK -> English only; QUALITY; no full-text indexing; self-contained first-party OCR and translation implementation**. All changes must be committed in the Nexus host repo, not left as an external workflow. This contract extends the existing Nexus fusion; it is **not** authorization to restart either Downloader or Rounder, to erase stored assets, or to invent a sixth navigation tab. Apply it to the one native Nexus Android app when implementing its Library/reader and verified-file registry. No successful translation or phone delivery is implied by this document.

## 1. Frozen product settings

| Parameter | Frozen value |
| --- | --- |
| Automatic translation | **CJK ONLY**: detected Japanese (`ja`), Korean (`ko`), simplified Chinese (`zh-Hans`) and traditional Chinese (`zh-Hant`) into English. Mixed-script CJK books remain eligible. |
| Target language | **English (`en`)** |
| Translation mode | **QUALITY**, multi-pass contextual translation, review and typesetting |
| Preserve originals | **TRUE**, immutable source media |
| Index source/translated text | **FALSE**; no searchable text index or full-text search insertion |
| Eligible inputs | Only **verified complete** books/comics from Rounder's unified file registry |
| Input formats | **EPUB, PDF, CBZ, CBR, standalone images, ZIP, TXT** |
| OCR | Only when direct text extraction is unavailable/insufficient |
| Queue | Persistent, independent, parallel and resumable; independent of Downloader acquisitions |
| Execution | **Phone-native offline**; optional Windows acceleration; never required |
| Runtime dependencies | **NONE outside the Nexus application, operating-system APIs and packaged app assets** |
| Required language coverage | Detect source language; automatically queue **Japanese, Korean and Chinese only**. Non-CJK books are not auto-queued; explicit unsupported language requests produce a visible failure, never a false English result. |

The owner specifically rejects an external translator, external translation/OCR engine (including a repackaged third-party engine), separate companion app, web API, hosted model/service, user-installed tool, and an automatic dependency on a PC. **All required translation/OCR executables, inference runtimes, codecs and model assets must be shipped, stored and executed inside Nexus**. The OCR and translation execution implementations must be first-party Nexus source modules: **do not wire, launch, vendor, bundle or wrap a third-party OCR/translation engine**, even for offline use. Standard build-time libraries for document decoding, codecs, math, and other ancillary functions may be integrated with license compliance, but cannot introduce a separate translation/OCR engine or runtime dependency. Android system APIs are allowed. Prefer native source modules and explicit ABI assets over invoking another installed app or external CLI.

**Important feasibility distinction:** Compiling code alone does not produce high-quality translations of arbitrary languages; learned vocabulary/weights and OCR resources must exist *inside* the installable product. No placeholder, rule-only dictionary, system WebView translation, empty model wrapper or network-only fallback is acceptable as a production-quality translator. Model packs may be distributed as part of a verified signed Nexus update, then reside in app-controlled local storage; first run and subsequent translation must not require an external service. Any language not supported by installed assets must report `UNSUPPORTED_LANGUAGE`, and the original must remain intact.

## 2. Non-destructive integration

1. Downloader completes source acquisition and its verification independently, preserving its own parallel download agents. **Downloading does not await translation.**
2. The merged Rounder inventory records source URI, provenance, stable file ID, byte size, source hash/verification receipt, MIME/format and local readability. **An upload request, temporary download, source listing or cloud-only placeholder is not verified-complete input.** Cloud-only files are eligible only after a locally verified complete copy is available or a verified bounded local reader stream is materialized.
3. A separate native `TranslationCoordinator` consumes stable `LOCAL_VERIFIED` / verified-complete registry events and scans the existing eligible library on request. Idempotency key: original file ID + original content hash + target language + translation-model/content-pipeline versions. Queue repeated events without duplicating output.
4. Emit new **derived edition** entries linked to the original, never overwrite, mutate or delete the source. Preserve source page sequence, chapter ordering, artwork, bookmarks/reader location where feasible, creation/author/title metadata, source attribution, and provenance. No automatic duplicate deletion.
5. Library/reader displays original and translated variants as one related book/comic; language/translation status is an item-level action or status, **not** a new top-level tab. The existing approved Rounder cards remain unchanged.
6. Translation receipts are visible only for translation state and derived-file verification, never counted as a successful download, upload or cloud storage operation.

## 3. Internal implementation boundaries (all shipped in Nexus)

- `FormatReader`: safe EPUB/CBZ/ZIP unpacking; PDF text-layer access and raster pages; CBR/RAR unpacking via an integrated decoder; TXT encoding detection; image decoding. Bound memory; prevent archive path traversal, recursion, decompression bombs and oversized pages.
- `LanguageClassifier`: run on extracted/OCR text samples; handle per-page or per-block mixed source languages, scripts and vertical writing; English-only items are marked `SKIPPED_ALREADY_ENGLISH`.
- `NativePageOcr`: internally compiled/integrated document and comic text detector/recognizer with packaged offline weights; geometry, reading order, vertical Japanese, simplified/traditional Chinese, Korean, ruby/furigana and dialogue boxes must be tested; do not OCR books with reliable text layers.
- `NativeTranslator`: internally executed multilingual context-aware translation and language-model resources packaged with Nexus; support CJK-to-English first with coverage expansion only for Japanese, Korean, and simplified/traditional Chinese; long-book segmentation with chapter context, consistency lexicon, proper-name retention and multi-pass review in QUALITY mode.
- `EditionComposer`: preserve original art, erase/inpaint text regions non-destructively on copies and typeset English at matching reading order for comics; EPUB/TXT output retains chapter/paragraph structure; PDF retains page order and appropriate page sizing. Never save a low-fidelity plain-text dump as if it were a completed comic-page rebuild.
- `TranslationCoordinator`: bounded worker pool independent of network/download workers, persistent task/checkpoint journal, pause/resume/cancel/retry, crash replay, power/thermal/memory throttling, optional manually approved Windows worker with parity receipts and no mandatory connectivity.
- `DerivedEditionStore`: atomic staging and verified promotion of completed outputs; hash/size receipts, model/version provenance, source hash, target locale, page count and per-page failures; rollback partial writes and preserve source.

For Android, favor Kotlin/JVM coordination with native on-device computational paths as appropriate, using packaged Android libraries/models and CPU/GPU/NPU acceleration **only where supported and actually verified**. No deceptive claim of neural accelerator use.

## 4. Deterministic state machine

`DISCOVERED -> ELIGIBILITY_CHECK -> QUEUED -> READING -> LANGUAGE_DETECTION -> TEXT_EXTRACTION_OR_OCR -> TRANSLATING -> REVIEWING -> COMPOSING -> OUTPUT_VERIFYING -> COMPLETE`

Alternative terminal/recoverable states: `SKIPPED_ALREADY_ENGLISH`, `SKIPPED_OUT_OF_SCOPE_LANGUAGE`, `UNSUPPORTED_LANGUAGE`, `UNSUPPORTED_FORMAT`, `WAITING_FOR_LOCAL_COPY`, `MISSING_PACKAGED_ASSETS`, `PAUSED`, `CANCELLED`, `FAILED_RETRYABLE`, `FAILED_PERMANENT`.

- Persist input and checkpoint receipt with safe transaction ordering. A task cannot be marked `COMPLETE` until the derived edition can be reopened, decoded, has the expected sequence/pages, and its output checksum/size are verified.
- Failure must display a precise reason, preserve original data and be retryable when appropriate; no fabricated page translations or invented OCR text.
- Prefer parallelism across distinct books and bounded pages, with order-preserving composition. Use independent CPU/IO worker budgets; high quality must not freeze UI, compromise download throughput or exhaust memory.
- Never expose progress percentages based only on enqueue/handoff. Report verified pages/chapters or honest indeterminate state.
- An optional Windows worker may consume explicit, authenticated, versioned work packages and return signed/verified output receipts; all features must remain runnable locally without Windows.

## 5. Text privacy and indexing

- **Do not index** recognized, original or translated full text in Nexus search, Core, TeraBox, telemetry, metadata or any other search index. This supersedes previous suggestions to index both.
- Store only transient or job-scoped OCR/context buffers and securely clean them after the derived edition is safely written, consistent with resumability; keep original/derived files, provenance and minimal technical job metadata.
- Search by normal file metadata (title, author, format, tags, path and source) remains unaffected. No cross-app transfer of extracted text without explicit user action.

## 6. Output rules by format

| Input | Required native handling | Output |
| --- | --- | --- |
| EPUB | Extract existing XHTML text, preserve navigation/chapters/images/styles where possible | Linked English EPUB |
| PDF | Prefer text layer; OCR page images/scans; preserve geometry and page ordering | Linked readable English PDF |
| CBZ / ZIP comics | Correct image ordering, OCR bubbles, non-destructive inpainting and English typesetting | Linked English CBZ (or equivalent reader-native edition) |
| CBR | Decode RAR internally, same comic treatment | Linked English comic edition |
| PNG / JPEG / WebP and other supported images | OCR only if needed; preserve dimensions and full artwork, no crops | Linked English image |
| TXT | Detect encoding, paragraphs and chapters; translate directly | Linked English TXT |

ZIP archives that are not eligible books/comics must not be rewritten blindly. Unsupported/incompatible archives return a meaningful status; never imply universal decoding based on file extensions alone.

## 7. Acceptance gates (none met by writing this contract)

- Implement and unit-test the full native pipeline and the embedded model runtime. Ship actual permitted offline language and OCR model assets; verify exact APK/AAB asset sizes, signing, licensing, supported ABIs and first-run availability **without internet**.
- End-to-end tests on actual verified EPUB, text PDF, scanned PDF, CBZ, CBR, standalone image, ZIP comic and TXT; include Japanese vertical manga, Korean webtoon and simplified/traditional Chinese comic examples.
- Test that English is skipped and that other non-CJK languages are **not auto-translated** or automatically queued; explicit unsupported translation requests must report `UNSUPPORTED_LANGUAGE`. Include mixed-script CJK materials.
- Compare semantic fidelity, names, dialogue context, speech-bubble geometry, missing lines, reading order and typography against human-translated references. QUALITY mode cannot mean a mere slower UI setting.
- Test >1 simultaneous translation while >1 download runs; pause/resume/retry/cancel after process death, partial output recovery, thermal constraint, phone offline, and Windows unavailable. No data loss and no download stall.
- Confirm **zero full-text indexing**, unchanged originals, verified derived outputs, correct provenance and no duplicate versions from repeat registry events.
- Use `NOT STARTED / IMPLEMENTED / CI VERIFIED / DEVICE VERIFIED / BLOCKED` evidence labels; never claim production-ready based on compiling an interface or downloading an external model.

## 8. Migration and priority

Preserve the N0–N8 Nexus roadmap. Record this subsystem as **Nexus Library/reader + verified-inventory post-processing scope**, not a replacement for N0–N4. First fix verified Downloader delivery and Rounder integration; parallel native translation can be developed without blocking their throughput. Do not declare Nexus or translation complete until APK and real-phone verification pass.

**Current evidence 2026-11-11:** Contract only. The merged Nexus product, embedded OCR/translation runtime, packaged language assets and functional reader-side translation have not been verified or delivered here.
