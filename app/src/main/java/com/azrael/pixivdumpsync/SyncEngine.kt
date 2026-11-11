package com.azrael.pixivdumpsync

import android.content.Context
import java.net.UnknownHostException
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SyncEngine(private val context: Context) {
    data class Stats(
        var artists: Int = 0,
        var worksSeen: Int = 0,
        var worksCompleted: Int = 0,
        var pagesDownloaded: Int = 0,
        var skippedDone: Int = 0,
        var skippedUgoira: Int = 0,
        var skippedTags: Int = 0,
        var cloudUploaded: Int = 0,
        var cloudErrors: Int = 0,
        var errors: Int = 0
    )

    private enum class WorkResult {
        DONE,
        SKIPPED,
        STOPPED
    }

    private data class WorkTask(
        val artistId: String,
        val id: String
    )

    private data class ParallelBatchResult(
        val hadError: Boolean
    )


    fun run(
        mode: SyncMode,
        selectedOnly: Boolean = false,
        targetArtistId: String? = null,
        targetArtworkId: String? = null,
        feedOnly: Boolean = false,
        maxArtists: Int? = null,
        artistOffset: Int = 0,
        queueLiveIfBusy: Boolean = true,
        progress: (String) -> Unit = {}
    ): Stats {
        val stats = Stats()

        if (!SyncControl.tryStart(mode, queueLiveIfBusy = queueLiveIfBusy)) {
            val s = SyncControl.snapshot()
            progress(
                if (mode == SyncMode.LIVE && s.pendingLive) {
                    "Live check queued behind the active sync."
                } else {
                    "Another sync is already running."
                }
            )
            return stats
        }

        NetworkCookies.install(context)
        val db = AppDb(context)
        db.clearTransientNetworkErrors()
        val api = if (mode == SyncMode.CLOUD) null else PixivApi(context)
        var finalMessage = "Idle"
        var released = false

        try {
            when (mode) {
                SyncMode.CLOUD -> drainCloudQueue(stats, progress, 10000)
                SyncMode.LIVE -> runLivePass(
                    db = db,
                    api = requireNotNull(api),
                    selectedOnly = selectedOnly,
                    targetArtistId = targetArtistId,
                    feedOnly = feedOnly,
                    maxArtists = maxArtists,
                    artistOffset = artistOffset,
                    stats = stats,
                    progress = progress
                )
                SyncMode.BACKFILL -> runBackfillPass(
                    db = db,
                    api = requireNotNull(api),
                    targetArtistId = targetArtistId,
                    stats = stats,
                    progress = progress
                )
                SyncMode.DIRECT -> runDirectPass(
                    db = db,
                    api = requireNotNull(api),
                    targetArtistId = requireNotNull(targetArtistId) {
                        "Direct download requires an artist ID"
                    },
                    targetArtworkId = requireNotNull(targetArtworkId) {
                        "Direct download requires an artwork ID"
                    },
                    stats = stats,
                    progress = progress
                )
            }

            finalMessage = if (SyncControl.snapshot().stopping) {
                "Stopped"
            } else {
                "${modeLabel(mode)} complete"
            }

            if (mode != SyncMode.CLOUD && !SyncControl.snapshot().stopping) {
                drainCloudQueue(stats, progress, 20)
            }

            while (
                !SyncControl.snapshot().stopping &&
                SyncControl.completeOrContinueWithQueuedLive(finalMessage)
            ) {
                progress("Running queued Live Sync…")
                runLivePass(
                    db = db,
                    api = requireNotNull(api),
                    selectedOnly = false,
                    targetArtistId = null,
                    feedOnly = false,
                    maxArtists = null,
                    artistOffset = 0,
                    stats = stats,
                    progress = progress
                )
                if (!SyncControl.snapshot().stopping) {
                    drainCloudQueue(stats, progress, 20)
                }
                finalMessage = "Live sync complete"
            }

            if (SyncControl.snapshot().running) {
                SyncControl.forceFinish(finalMessage)
            }
            released = true
            if (stats.pagesDownloaded > 0 && mode != SyncMode.CLOUD &&
                NduSettings.configured(context) && finalMessage != "Stopped") {
                runCatching { NduUploadService.launch(context) }
                    .onFailure { progress("NDUS upload queued; open Cloud backup to retry") }
            }
            return finish(stats, mode)
        } finally {
            db.close()
            if (!released && SyncControl.snapshot().running) {
                SyncControl.forceFinish(finalMessage)
            }
        }
    }

    private fun runLivePass(
        db: AppDb,
        api: PixivApi,
        selectedOnly: Boolean,
        targetArtistId: String?,
        feedOnly: Boolean,
        maxArtists: Int?,
        artistOffset: Int,
        stats: Stats,
        progress: (String) -> Unit
    ) {
        SyncControl.setMode(SyncMode.LIVE)

        if (
            targetArtistId == null &&
            (feedOnly || SessionStore.followingFeedEnabled(context))
        ) {
            val feedMessage = "Following feed • checking newest works"
            SyncControl.updateMessage(feedMessage)
            progress(feedMessage)
            try {
                syncFollowingFeed(db, api, stats, progress)
            } catch (t: Throwable) {
                if (SyncControl.isStopping()) return
                stats.errors++
                progress("Following feed error: ${t.message}")
                if (isGlobalNetworkError(t)) return
                if (t is HttpStatusException && t.code == 429) {
                    progress("Pixiv rate-limited this run; it will retry later.")
                    return
                }
            }
        }

        if (feedOnly) {
            return
        }

        val allArtists = when {
            targetArtistId != null -> listOfNotNull(db.artist(targetArtistId))
            selectedOnly -> db.listSelectedLiveArtists()
            else -> db.listLiveArtists()
        }
        val artists = if (
            maxArtists != null &&
            maxArtists > 0 &&
            allArtists.size > maxArtists
        ) {
            val start = artistOffset % allArtists.size
            List(maxArtists.coerceAtMost(allArtists.size)) { index ->
                allArtists[(start + index) % allArtists.size]
            }
        } else {
            allArtists
        }

        stats.artists = maxOf(stats.artists, artists.size)

        for ((artistIndex, artist) in artists.withIndex()) {
            if (!SyncControl.checkpoint()) return

            val name = artist.label ?: "Pixiv user ${artist.userId}"
            val message =
                "Live sync • ${artistIndex + 1}/${artists.size} • $name"

            SyncControl.updateMessage(message)
            progress(message)

            try {
                syncLiveArtist(db, api, artist, stats, progress)
            } catch (t: Throwable) {
                if (SyncControl.isStopping()) return
                stats.errors++
                db.setArtistError(
                    artist.userId,
                    t.message ?: "Unknown error"
                )
                progress("Error for $name: ${t.message}")

                if (isGlobalNetworkError(t)) return
                if (t is HttpStatusException && t.code == 429) {
                    progress("Pixiv rate-limited this run; it will retry later.")
                    return
                }
            }
        }
    }

    private fun syncFollowingFeed(
        db: AppDb,
        api: PixivApi,
        stats: Stats,
        progress: (String) -> Unit
    ) {
        val cursor = SessionStore.followingFeedCursor(context)
        val allItems = mutableListOf<PixivApi.ArtworkPreview>()
        var newestId: String? = null
        var pageNumber = 1

        while (true) {
            if (!SyncControl.checkpoint()) return

            val page = api.followingFeedPage(pageNumber)
            if (newestId == null) newestId = page.items.firstOrNull()?.id

            val previousSize = allItems.size
            allItems += page.items.filter { candidate ->
                allItems.none { it.id == candidate.id }
            }

            if (!cursor.isNullOrBlank() && page.items.any { it.id == cursor }) {
                break
            }

            if (cursor.isNullOrBlank()) break
            if (page.isLastPage || page.items.isEmpty()) break
            if (allItems.size == previousSize) break

            pageNumber++
        }

        if (cursor.isNullOrBlank()) {
            val pendingFromEarlierRun = allItems.filter { item ->
                db.hasWorkRecord(item.id) && !isCompleteOnDisk(db, item.id)
            }

            if (pendingFromEarlierRun.isNotEmpty()) {
                progress(
                    "Following feed • resuming ${pendingFromEarlierRun.size} incomplete work(s)"
                )

                val resumeResult = syncWorkTasksParallel(
                    tasks = pendingFromEarlierRun.asReversed().map {
                        WorkTask(it.userId, it.id)
                    },
                    stats = stats,
                    progressLabel = "Following feed resume",
                    progress = progress
                )

                if (resumeResult.hadError) {
                    progress("Following feed • incomplete work(s) still pending")
                    return
                }
            }

            if (newestId != null) {
                SessionStore.setFollowingFeedCursor(context, newestId)
                progress("Following feed ready • baseline established")
            } else {
                progress("Following feed • no works returned")
            }
            return
        }

        val newItems = allItems.takeWhile { it.id != cursor }

        if (newItems.isEmpty()) {
            progress("Following feed • no new works")
            return
        }

        val feedResult = syncWorkTasksParallel(
            tasks = newItems.asReversed().map {
                WorkTask(it.userId, it.id)
            },
            stats = stats,
            progressLabel = "Following feed",
            progress = progress
        )

        if (!feedResult.hadError && newestId != null) {
            SessionStore.setFollowingFeedCursor(context, newestId)
        }
    }

    private fun runDirectPass(
        db: AppDb,
        api: PixivApi,
        targetArtistId: String,
        targetArtworkId: String,
        stats: Stats,
        progress: (String) -> Unit
    ) {
        SyncControl.setMode(SyncMode.DIRECT)

        if (!SyncControl.checkpoint()) return

        stats.artists = maxOf(stats.artists, 1)
        stats.worksSeen++

        if (isCompleteOnDisk(db, targetArtworkId)) {
            stats.skippedDone++
            val message = "Artwork $targetArtworkId is already downloaded"
            SyncControl.updateMessage(message)
            progress(message)
            return
        }

        val message = "Downloading artwork $targetArtworkId…"
        SyncControl.updateMessage(message)
        progress(message)

        when (syncWork(db, api, targetArtistId, targetArtworkId, stats)) {
            WorkResult.DONE -> {
                val done = "Artwork $targetArtworkId downloaded"
                SyncControl.updateMessage(done)
                progress(done)
            }
            WorkResult.SKIPPED -> {
                val skipped = "Artwork $targetArtworkId was skipped"
                SyncControl.updateMessage(skipped)
                progress(skipped)
            }
            WorkResult.STOPPED -> Unit
        }
    }

    private fun runBackfillPass(
        db: AppDb,
        api: PixivApi,
        targetArtistId: String?,
        stats: Stats,
        progress: (String) -> Unit
    ) {
        SyncControl.setMode(SyncMode.BACKFILL)

        val artists = if (targetArtistId != null) {
            listOfNotNull(db.artist(targetArtistId))
        } else {
            db.listSelectedArtists()
        }
        stats.artists = maxOf(stats.artists, artists.size)

        for ((artistIndex, artist) in artists.withIndex()) {
            if (!SyncControl.checkpoint()) return

            val name = artist.label ?: "Pixiv user ${artist.userId}"
            val message =
                "Archive backfill • ${artistIndex + 1}/${artists.size} • $name"

            SyncControl.updateMessage(message)
            progress(message)

            try {
                syncBackfillArtist(db, api, artist, stats, progress)
            } catch (t: Throwable) {
                if (SyncControl.isStopping()) return
                stats.errors++
                db.setArtistError(
                    artist.userId,
                    t.message ?: "Unknown error"
                )
                progress("Error for $name: ${t.message}")

                if (isGlobalNetworkError(t)) return
                if (t is HttpStatusException && t.code == 429) {
                    progress("Pixiv rate-limited this run; it will retry later.")
                    return
                }
            }

            serviceQueuedLiveDuringBackfill(db, api, stats, progress)
        }
    }

    private fun serviceQueuedLiveDuringBackfill(
        db: AppDb,
        api: PixivApi,
        stats: Stats,
        progress: (String) -> Unit
    ) {
        while (SyncControl.takePendingLive()) {
            if (!SyncControl.checkpoint()) return

            progress("Backfill paused for queued Live Sync…")
            runLivePass(
                db = db,
                api = api,
                selectedOnly = false,
                targetArtistId = null,
                feedOnly = false,
                maxArtists = null,
                artistOffset = 0,
                stats = stats,
                progress = progress
            )

            if (!SyncControl.checkpoint()) return
            SyncControl.setMode(SyncMode.BACKFILL)
            progress("Resuming archive backfill…")
        }
    }

    private fun syncLiveArtist(
        db: AppDb,
        api: PixivApi,
        artist: ArtistRecord,
        stats: Stats,
        progress: (String) -> Unit
    ) {
        val snapshot = api.userArtworkSnapshot(
            artist.userId,
            previewLimit = 5,
            resolveMissingPreviews = true
        )
        val ids = snapshot.ids
        val newest = ids.firstOrNull()

        if (snapshot.previews.any { !it.thumbnailUrl.isNullOrBlank() }) {
            db.updateArtistPresentation(
                artist.userId,
                previewJson = ArtworkPreviewCodec.encode(snapshot.previews)
            )
        }

        if (artist.liveCursor.isNullOrBlank()) {
            db.setLiveCursor(artist.userId, newest)
            db.markArtistChecked(artist.userId)

            progress(
                if (newest == null) {
                    "${artist.label ?: artist.userId}: live watch ready; no illustrations yet."
                } else {
                    "${artist.label ?: artist.userId}: live watch baseline established."
                }
            )
            return
        }

        val newIds = LiveCursorLogic.processingOrder(ids, artist.liveCursor)

        if (newIds.isNotEmpty()) {
            val refreshedPreviews = runCatching {
                api.userArtworkSnapshot(
                    artist.userId,
                    previewLimit = 5,
                    resolveMissingPreviews = true
                ).previews
            }.getOrDefault(emptyList())

            if (refreshedPreviews.any { !it.thumbnailUrl.isNullOrBlank() }) {
                db.updateArtistPresentation(
                    artist.userId,
                    previewJson = ArtworkPreviewCodec.encode(refreshedPreviews)
                )
            }
        }

        if (newIds.isEmpty()) {
            db.markArtistChecked(artist.userId)
            progress("${artist.label ?: artist.userId}: no new illustrations.")
            return
        }

        for ((index, id) in newIds.withIndex()) {
            if (!SyncControl.checkpoint()) return

            stats.worksSeen++
            val message =
                "Live • ${artist.label ?: artist.userId} • ${index + 1}/${newIds.size}"

            SyncControl.updateMessage(message)
            progress(message)

            try {
                if (isCompleteOnDisk(db, id)) {
                    stats.skippedDone++
                    db.setLiveCursor(artist.userId, id)
                    continue
                }

                when (syncWork(db, api, artist.userId, id, stats)) {
                    WorkResult.DONE, WorkResult.SKIPPED -> {
                        db.setLiveCursor(artist.userId, id)
                        db.setArtistError(artist.userId, null)
                    }
                    WorkResult.STOPPED -> return
                }
            } catch (t: Throwable) {
                if (SyncControl.isStopping()) return
                db.setArtistError(
                    artist.userId,
                    t.message ?: "Unknown error"
                )
                stats.errors++

                if (t is HttpStatusException && t.code == 429) {
                    throw t
                }
                return
            }
        }

        db.markArtistChecked(artist.userId)
    }

    private fun syncBackfillArtist(
        db: AppDb,
        api: PixivApi,
        artist: ArtistRecord,
        stats: Stats,
        progress: (String) -> Unit
    ) {
        val snapshot = api.userArtworkSnapshot(
            artist.userId,
            previewLimit = 5,
            resolveMissingPreviews = false
        )
        val ids = snapshot.ids

        if (snapshot.previews.isNotEmpty()) {
            db.updateArtistPresentation(
                artist.userId,
                previewJson = ArtworkPreviewCodec.encode(snapshot.previews)
            )
        }

        db.setArchiveMeta(
            artist.userId,
            status = "RUNNING",
            knownTotal = ids.size,
            error = null
        )

        if (!SyncControl.checkpoint()) {
            db.setArchiveMeta(
                artist.userId,
                "PARTIAL",
                ids.size,
                null
            )
            return
        }

        val errorsBefore = stats.errors
        syncWorkTasksParallel(
            tasks = ids.map { WorkTask(artist.userId, it) },
            stats = stats,
            progressLabel = "Backfill • ${artist.label ?: artist.userId}",
            progress = progress
        )
        val artistErrors = stats.errors - errorsBefore

        if (SyncControl.isStopping()) {
            db.setArchiveMeta(
                artist.userId,
                "PARTIAL",
                ids.size,
                null
            )
            return
        }

        val p = db.artistProgress(artist.userId, ids.size)
        val complete = p.done + p.skipped >= ids.size

        val status = when {
            complete -> "COMPLETE"
            artistErrors > 0 && p.done == 0 -> "ERROR"
            else -> "PARTIAL"
        }

        db.setArchiveMeta(
            artist.userId,
            status = status,
            knownTotal = ids.size,
            error = if (complete) null else artist.lastError
        )
    }

    private fun syncWorkTasksParallel(
        tasks: List<WorkTask>,
        stats: Stats,
        progressLabel: String,
        progress: (String) -> Unit
    ): ParallelBatchResult {
        if (tasks.isEmpty()) return ParallelBatchResult(hadError = false)

        val workerCount = minOf(MAX_PARALLEL_WORKS, tasks.size)
        val nextIndex = AtomicInteger(0)
        val completedCount = AtomicInteger(0)
        val hadError = AtomicBoolean(false)
        val fatal = AtomicReference<Throwable?>(null)
        val executor = Executors.newFixedThreadPool(workerCount)

        try {
            val futures = List(workerCount) {
                executor.submit {
                    val localStats = Stats()
                    AppDb(context).use { workerDb ->
                        val workerApi = PixivApi(context)

                        while (
                            fatal.get() == null &&
                            SyncControl.checkpoint()
                        ) {
                            val index = nextIndex.getAndIncrement()
                            if (index >= tasks.size) break

                            val task = tasks[index]
                            localStats.worksSeen++

                            try {
                                if (isCompleteOnDisk(workerDb, task.id)) {
                                    localStats.skippedDone++
                                } else {
                                    when (
                                        syncWork(
                                            db = workerDb,
                                            api = workerApi,
                                            expectedArtistId = task.artistId,
                                            id = task.id,
                                            stats = localStats
                                        )
                                    ) {
                                        WorkResult.DONE,
                                        WorkResult.SKIPPED -> Unit
                                        WorkResult.STOPPED -> break
                                    }
                                }
                            } catch (t: Throwable) {
                                if (SyncControl.isStopping()) break
                                hadError.set(true)
                                localStats.errors++
                                workerDb.setArtistError(
                                    task.artistId,
                                    t.message ?: "Unknown error"
                                )
                                progress(
                                    "$progressLabel error ${task.id}: ${t.message ?: t.javaClass.simpleName}"
                                )

                                if (
                                    t is HttpStatusException && t.code == 429 ||
                                    isGlobalNetworkError(t)
                                ) {
                                    fatal.compareAndSet(null, t)
                                }
                            } finally {
                                val done = completedCount.incrementAndGet()
                                val message = "$progressLabel • $done/${tasks.size}"
                                SyncControl.updateMessage(message)
                                progress(message)
                            }
                        }
                    }

                    synchronized(stats) {
                        stats.worksSeen += localStats.worksSeen
                        stats.worksCompleted += localStats.worksCompleted
                        stats.pagesDownloaded += localStats.pagesDownloaded
                        stats.skippedDone += localStats.skippedDone
                        stats.skippedUgoira += localStats.skippedUgoira
                        stats.skippedTags += localStats.skippedTags
                        stats.errors += localStats.errors
                    }
                }
            }

            futures.forEach { future ->
                runCatching { future.get() }
                    .onFailure { error ->
                        hadError.set(true)
                        fatal.compareAndSet(null, error.cause ?: error)
                    }
            }
        } finally {
            executor.shutdownNow()
        }

        fatal.get()?.let { throw it }
        return ParallelBatchResult(hadError = hadError.get())
    }

    private fun syncWork(
        db: AppDb,
        api: PixivApi,
        expectedArtistId: String,
        id: String,
        stats: Stats
    ): WorkResult {
        if (!SyncControl.checkpoint()) {
            return WorkResult.STOPPED
        }

        val detail = api.artworkDetail(id)

        if (detail.userId != expectedArtistId) {
            return WorkResult.SKIPPED
        }

        val blockedTag = TagExclusionPolicy.firstBlockedTag(
            detail.tags,
            SessionStore.excludedTags(context)
        )
        if (blockedTag != null) {
            db.upsertWork(id, expectedArtistId, detail.title, "SKIPPED_TAG", 0)
            stats.skippedTags++
            return WorkResult.SKIPPED
        }

        if (detail.illustType == 2) {
            db.upsertWork(
                id,
                expectedArtistId,
                detail.title,
                "SKIPPED_UGOIRA",
                0
            )
            stats.skippedUgoira++
            return WorkResult.SKIPPED
        }

        val urls = if (
            detail.pageCount == 1 &&
            !detail.originalUrl.isNullOrBlank()
        ) {
            listOf(requireNotNull(detail.originalUrl))
        } else {
            api.pageOriginalUrls(id)
        }

        db.upsertWork(
            id,
            expectedArtistId,
            detail.title,
            "DOWNLOADING",
            urls.size
        )

        for ((pageIndex, imageUrl) in urls.withIndex()) {
            if (!SyncControl.checkpoint()) {
                return WorkResult.STOPPED
            }

            val ext = FileStore.extensionFromUrl(imageUrl)
            val filename = "${id}_p${pageIndex}.$ext"
            val onDisk = FileStore.exists(context, filename)

            if (db.isPageSaved(id, pageIndex) && onDisk) {
                continue
            }

            if (!onDisk) {
                FileStore.saveImage(
                    context = context,
                    imageUrl = imageUrl,
                    filename = filename,
                    referer = "https://www.pixiv.net/artworks/$id",
                    knownMissing = true
                )
                stats.pagesDownloaded++
            }

            db.markPageSaved(
                id,
                pageIndex,
                filename
            )
            if (TeraboxSettings.enabled(context)) {
                CloudUploadQueue(context).use { it.enqueue(filename) }
            }
        }

        if (!SyncControl.checkpoint()) {
            return WorkResult.STOPPED
        }

        db.setWorkState(
            id,
            "DOWNLOADED_UNBOOKMARKED"
        )

        if (!detail.isBookmarked) {
            api.bookmark(id)
        }

        db.setWorkState(
            id,
            "DONE",
            bookmarkedMarked = true
        )

        stats.worksCompleted++
        return WorkResult.DONE
    }

    private fun isCompleteOnDisk(
        db: AppDb,
        illustId: String
    ): Boolean {
        if (!db.isWorkDoneAndBookmarked(illustId)) {
            return false
        }

        val expected =
            db.workPageCount(illustId)
                ?: return false

        if (expected <= 0) {
            return false
        }

        val files =
            db.savedPageFilenames(illustId)

        if (files.size != expected) {
            return false
        }

        return files.all {
            FileStore.exists(context, it)
        }
    }

    private fun drainCloudQueue(
        stats: Stats,
        progress: (String) -> Unit,
        maxItems: Int
    ) {
        if (!TeraboxSettings.enabled(context) || !TeraboxSettings.isConfigured(context)) {
            return
        }
        CloudUploadQueue(context).use { queue ->
            val uploads = queue.nextBatch(maxItems, System.currentTimeMillis())
            if (uploads.isEmpty()) return
            val client = TeraboxUploader(context)
            for ((index, item) in uploads.withIndex()) {
                if (!SyncControl.checkpoint()) return
                val message = "TeraBox upload ${index + 1}/${uploads.size}"
                SyncControl.updateMessage(message)
                progress(message)
                try {
                    val proof=if(item.state=="VERIFY_PENDING")
                        client.verifyHistoricalArtwork(item.filename,item.remotePath)
                    else client.uploadArtwork(item.filename)
                    queue.uploaded(item.filename,proof)
                    if(item.state!="VERIFY_PENDING")stats.cloudUploaded++
                } catch (t: Throwable) {
                    if (SyncControl.isStopping() ||
                        t is SyncCancelledException || t is InterruptedException
                    ) return
                    val message = t.message ?: "Cloud upload failed"
                    queue.failed(item.filename, message)
                    stats.cloudErrors++
                    progress("TeraBox: $message")
                    if (message.contains("token", ignoreCase = true) ||
                        message.contains("HTTP 401") ||
                        message.contains("HTTP 403") ||
                        message.contains("domain", ignoreCase = true)) break
                }
            }
        }
    }

    private fun finish(
        stats: Stats,
        mode: SyncMode
    ): Stats {
        val stamp =
            DateFormat.getDateTimeInstance(
                DateFormat.SHORT,
                DateFormat.SHORT
            ).format(Date())

        val summary =
            "$stamp — ${modeLabel(mode)}: " +
                "${stats.worksCompleted} completed, " +
                "${stats.pagesDownloaded} images, " +
                "${stats.skippedTags} tag-filtered, " +
                "${stats.cloudUploaded} cloud uploaded, " +
                "${stats.cloudErrors} cloud errors, " +
                "${stats.errors} errors"

        SessionStore.setLastSyncSummary(
            context,
            summary
        )

        return stats
    }

    private fun isGlobalNetworkError(t: Throwable): Boolean {
        var current: Throwable? = t
        while (current != null) {
            if (current is UnknownHostException) return true
            current = current.cause
        }
        return t.message?.contains("Unable to resolve host", ignoreCase = true) == true
    }

    private fun modeLabel(mode: SyncMode): String =
        when (mode) {
            SyncMode.LIVE -> "Live sync"
            SyncMode.BACKFILL -> "Archive backfill"
            SyncMode.DIRECT -> "Direct download"
        SyncMode.CLOUD -> "TeraBox upload"
        }

    companion object {
        private const val MAX_PARALLEL_WORKS = 3
    }
}
