package com.azrael.galleryflow

import android.content.Context

class SyncEngine(private val context: Context) {
    data class Stats(
        var entities: Int = 0,
        var galleriesSeen: Int = 0,
        var galleriesCompleted: Int = 0,
        var mediaDownloaded: Int = 0,
        var mediaDeduped: Int = 0,
        var galleriesSkippedTags: Int = 0,
        var errors: Int = 0,
        var incompleteGalleries: Int = 0
    )

    private val http = HttpClient()

    fun run(mode: SyncMode, targetEntityId: String? = null, progress: (String) -> Unit = {}): Stats {
        com.azrael.pixivdumpsync.BackfillPerformance.backgroundWorker()
        val stats = Stats()
        if (!SyncControl.tryStart(mode, queueLiveIfBusy = true)) return stats
        val db = GalleryDb(context)
        val files = MediaStoreFiles(context, http)
        val flow = FlowLinkEmitter(context, db)
        try {
            SyncControl.setMode(mode)
            when (mode) {
                SyncMode.LIVE -> runLive(db, files, flow, stats, progress, targetEntityId)
                SyncMode.BACKFILL -> runBackfill(db, files, flow, stats, progress, targetEntityId)
            }
            while (!SyncControl.isStopping() && SyncControl.takePendingLive()) {
                SyncControl.setMode(SyncMode.LIVE)
                progress("Running queued Live Sync…")
                runLive(db, files, flow, stats, progress)
            }
            if (!SyncControl.isStopping() && stats.mediaDownloaded > 0 &&
                com.azrael.pixivdumpsync.NduSettings.configured(context)) {
                runCatching {
                    com.azrael.pixivdumpsync.NduUploadService.launch(context)
                }.onFailure { progress("NDUS upload queued; open Cloud backup to retry") }
            }
            val label = if (stats.errors > 0 || stats.incompleteGalleries > 0)
                "Backfill incomplete: ${stats.errors} errors, " +
                    "${stats.incompleteGalleries} incomplete galleries. Check source details."
            else mode.name.lowercase().replaceFirstChar { it.uppercase() } +
                " complete: ${stats.galleriesCompleted} galleries, " +
                "${stats.galleriesSkippedTags} tag-skipped"
            SyncControl.finish(if (SyncControl.isStopping()) "Stopped" else label)
        } catch (t: Throwable) {
            if (!SyncControl.isStopping() && t !is SyncCancelledException) stats.errors++
            SyncControl.finish(if (SyncControl.isStopping()) "Stopped" else
                "Sync interrupted: " + (t.message ?: t.javaClass.simpleName))
        } finally {
            db.close()
        }
        return stats
    }

    private fun runLive(
        db: GalleryDb,
        files: MediaStoreFiles,
        flow: FlowLinkEmitter,
        stats: Stats,
        progress: (String) -> Unit,
        targetEntityId: String? = null
    ) {
        val entities = db.listEntities().filter {
            it.selected && it.liveEnabled && (targetEntityId == null || it.entityId == targetEntityId)
        }
        stats.entities = maxOf(stats.entities, entities.size)
        for ((entityIndex, entity) in entities.withIndex()) {
            if (!SyncControl.checkpoint()) return
            val adapter = AdapterRegistry.forSource(entity.source)
            if (!adapter.enabled) {
                db.setEntityError(entity.source, entity.entityId, adapter.statusLabel)
                continue
            }
            val message = "Live • " + (entityIndex + 1) + "/" + entities.size + " • " + entity.displayName
            SyncControl.updateMessage(message)
            progress(message)
            try {
                val galleries = adapter.enumerateGalleries(entity.toSourceEntity()).toList()
                val newest = galleries.firstOrNull()?.stableId
                if (entity.liveCursor.isNullOrBlank()) {
                    db.setLiveCursor(entity.source, entity.entityId, newest)
                    progress(entity.displayName + ": Live baseline established")
                    continue
                }
                val newOnes = galleries.takeWhile { it.stableId != entity.liveCursor }
                for (gallery in newOnes.asReversed()) {
                    if (!SyncControl.checkpoint()) return
                    if (processGallery(entity, gallery, adapter, db, files, flow, stats, progress)) {
                        db.setLiveCursor(entity.source, entity.entityId, gallery.stableId)
                    } else {
                        break
                    }
                }
                db.setEntityError(entity.source, entity.entityId, null)
            } catch (t: Throwable) {
                if (SyncControl.isStopping()) return
                stats.errors++
                db.setEntityError(entity.source, entity.entityId, t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private fun runBackfill(
        db: GalleryDb,
        files: MediaStoreFiles,
        flow: FlowLinkEmitter,
        stats: Stats,
        progress: (String) -> Unit,
        targetEntityId: String? = null
    ) {
        val entities = db.listEntities().filter {
            it.selected && (targetEntityId == null || it.entityId == targetEntityId)
        }
        stats.entities = maxOf(stats.entities, entities.size)
        for ((entityIndex, entity) in entities.withIndex()) {
            if (!SyncControl.checkpoint()) return
            val adapter = AdapterRegistry.forSource(entity.source)
            if (!adapter.enabled) {
                db.setEntityError(entity.source, entity.entityId, adapter.statusLabel)
                continue
            }
            val prefix = "Backfill • " + (entityIndex + 1) + "/" + entities.size + " • " + entity.displayName
            SyncControl.updateMessage(prefix)
            progress(prefix)
            try {
                val pages = adapter.enumerateGalleries(entity.toSourceEntity())
                var failedCount = 0
                // Retry a bounded batch of historical failures, then continue onward.
                for (retry in db.pendingRetries(entity)) {
                    if (!SyncControl.checkpoint()) return
                    progress("Retrying failed gallery: " + retry.title)
                    if (processGallery(entity, retry, adapter, db, files, flow, stats, progress)) {
                        db.clearRetry(retry)
                    } else {
                        failedCount++
                        stats.incompleteGalleries++
                        db.queueRetry(retry, "Gallery still incomplete")
                    }
                }
                for (gallery in pages) {
                    if (!SyncControl.checkpoint()) return
                    val message = entity.displayName + " / " +
                        stats.mediaDownloaded + " new images"
                    SyncControl.updateMessage(message)
                    progress(message)
                    val completed = processGallery(entity, gallery, adapter, db, files,
                        flow, stats, progress)
                    if (completed) {
                        db.clearRetry(gallery)
                    } else {
                        db.queueRetry(gallery, "Incomplete album or download error")
                        failedCount++
                        stats.incompleteGalleries++
                        progress("Queued failed album for retry: " + gallery.title)
                    }
                    if (serviceQueuedLive(db, files, flow, stats, progress)) {
                        SyncControl.setMode(SyncMode.BACKFILL)
                        SyncControl.updateMessage(prefix)
                        progress("Resuming " + prefix)
                    }
                }
                val retryCount = db.retryCount(entity.source, entity.entityId)
                db.setEntityError(entity.source, entity.entityId,
                    if (retryCount > 0) retryCount.toString() +
                        " galleries need retry; Backfill continued past them." else null)
                if (failedCount > 0) {
                    progress(failedCount.toString() + " galleries queued for retry.")
                }
            } catch (t: Throwable) {
                if (SyncControl.isStopping() || t is SyncCancelledException) return
                stats.errors++
                val reason = "Backfill failed: " +
                    (t.message ?: t.javaClass.simpleName)
                db.setEntityError(entity.source, entity.entityId, reason)
                SyncControl.updateMessage(reason)
                progress(reason)
            }
        }
    }

    private fun serviceQueuedLive(
        db: GalleryDb,
        files: MediaStoreFiles,
        flow: FlowLinkEmitter,
        stats: Stats,
        progress: (String) -> Unit
    ): Boolean {
        if (!SyncControl.takePendingLive()) return false
        SyncControl.setMode(SyncMode.LIVE)
        progress("Backfill yielded to queued Live Sync")
        runLive(db, files, flow, stats, progress)
        return true
    }

    private fun processGallery(
        entity: EntityRecord,
        ref: GalleryRef,
        adapter: SourceAdapter,
        db: GalleryDb,
        files: MediaStoreFiles,
        flow: FlowLinkEmitter,
        stats: Stats,
        progress: (String) -> Unit
    ): Boolean {
        stats.galleriesSeen++
        db.upsertGallery(ref, TransferState.PENDING)
        return try {
            val (meta, mediaItems) = adapter.fetchGallery(ref)
            db.updateGalleryMeta(meta, mediaItems.size)
            // Metadata is read before downloading any gallery image.
            val excluded = TagExclusions.firstBlocked(meta.tags, Prefs.effectiveExcludedTags(context, entity.entityId))
            if (excluded != null) {
                db.setGalleryState(meta.source, meta.stableId, TransferState.SKIPPED, "Excluded tag: $excluded")
                stats.galleriesSkippedTags++
                progress("Skipped tag '$excluded' • " + meta.title)
                return true // Advance Live cursor without downloading any files.
            }
            var allComplete = true
            for ((position, media) in mediaItems.withIndex()) {
                if (!SyncControl.checkpoint()) return false
                db.upsertMedia(entity.entityId, media)
                val blockedPost = TagExclusions.firstBlocked(
                    media.tags, Prefs.effectiveExcludedTags(context, entity.entityId))
                if (blockedPost != null) {
                    val saved = db.media(media.source, media.stableId)
                    if (saved?.state != TransferState.COMPLETE || !files.exists(saved.contentUri)) {
                        db.setMediaState(media.source, media.stableId, TransferState.SKIPPED)
                    }
                    continue
                }
                val existing = db.media(media.source, media.stableId)
                if (existing != null && existing.state == TransferState.COMPLETE && files.exists(existing.contentUri)) continue
                if (existing != null && !files.exists(existing.contentUri)) {
                    db.setMediaState(media.source, media.stableId, TransferState.PENDING)
                }

                SyncControl.updateMessage(meta.title + " • " + (position + 1) + "/" + mediaItems.size)
                db.markMediaDownloading(media)
                try {
                    val outcome = downloadWithRetry(files, entity, meta, media)
                    // Commit both the original asset and all genuine archive entries.
                    // Never treat a provider archive as an image or silently discard videos.
                    fun commitSaved(item: MediaRef, fresh: DownloadResult) {
                        val duplicate = db.findCompleteByHash(fresh.sha256)
                        if (duplicate != null && files.exists(duplicate.contentUri)) {
                            files.delete(fresh.contentUri)
                            db.markMediaComplete(
                                item,
                                fresh.copy(
                                    contentUri = requireNotNull(duplicate.contentUri),
                                    filename = duplicate.filename ?: fresh.filename
                                ),
                                duplicateOf = duplicate.source.wireName + ":" + duplicate.mediaId
                            )
                            stats.mediaDeduped++
                        } else {
                            db.markMediaComplete(item, fresh)
                            stats.mediaDownloaded++
                            if (com.azrael.pixivdumpsync.NduSettings.configured(context)) {
                                runCatching {
                                    com.azrael.pixivdumpsync.NduCloudQueue(context).use {
                                        it.enqueue("kyora", fresh.filename,
                                            android.net.Uri.parse(fresh.contentUri))
                                    }
                                }
                            }
                            flow.mediaDownloaded(entity, meta, item, fresh)
                        }
                    }
                    commitSaved(media, outcome.primary)
                    for (extracted in outcome.extracted) {
                        db.upsertMedia(entity.entityId, extracted.media)
                        commitSaved(extracted.media, extracted.result)
                    }
                } catch (e: HttpStatusException) {
                    allComplete = false
                    val state = when (e.code) {
                        401, 403, 404, 410 -> TransferState.INACCESSIBLE
                        429, 500, 502, 503, 504 -> TransferState.RETRYING
                        else -> TransferState.PARTIAL
                    }
                    db.setMediaState(media.source, media.stableId, state)
                    if (e.code == 429) break
                } catch (t: Throwable) {
                    if (SyncControl.isStopping() || t is SyncCancelledException) return false
                    allComplete = false
                    db.setMediaState(media.source, media.stableId, TransferState.RETRYING)
                }
            }

            val records = db.galleryMedia(meta.source, meta.stableId)
            val diskComplete = records.isNotEmpty() &&
                records.size >= mediaItems.size &&
                records.all {
                    it.state == TransferState.SKIPPED ||
                        (it.state == TransferState.COMPLETE && files.exists(it.contentUri))
                }

            if (allComplete && diskComplete) {
                db.setGalleryState(meta.source, meta.stableId, TransferState.COMPLETE)
                stats.galleriesCompleted++
                flow.galleryDownloaded(entity, meta)
                progress("Complete • " + meta.title)
                true
            } else {
                db.setGalleryState(meta.source, meta.stableId, TransferState.PARTIAL)
                false
            }
        } catch (e: HttpStatusException) {
            stats.errors++
            val state = if (e.code in listOf(401,403,404,410)) TransferState.INACCESSIBLE else TransferState.RETRYING
            db.setGalleryState(ref.source, ref.stableId, state, e.message)
            false
        } catch (t: Throwable) {
            if (SyncControl.isStopping() || t is SyncCancelledException) return false
            stats.errors++
            db.setGalleryState(ref.source, ref.stableId, TransferState.RETRYING, t.message ?: t.javaClass.simpleName)
            false
        }
    }

    private fun downloadWithRetry(files: MediaStoreFiles, entity: EntityRecord,
                                  meta: GalleryMeta, media: MediaRef): DownloadOutcome {
        var last: Throwable? = null
        for (attempt in 0..3) {
            if (!SyncControl.checkpoint()) throw SyncCancelledException()
            try {
                return files.download(entity, meta, media)
            } catch (e: HttpStatusException) {
                if (e.code !in listOf(408, 429, 500, 502, 503, 504)) throw e
                last = e
            } catch (e: java.io.IOException) {
                last = e
            }
            if (attempt < 3) {
                val pauseMs = if (last is HttpStatusException &&
                    (last as HttpStatusException).code == 429) 5000L
                    else 1200L * (attempt + 1)
                var slept = 0L
                while (slept < pauseMs) {
                    if (!SyncControl.checkpoint()) throw SyncCancelledException()
                    Thread.sleep(200L)
                    slept += 200L
                }
            }
        }
        throw (last ?: java.io.IOException("Transfer retry exhausted"))
    }

    private fun EntityRecord.toSourceEntity() =
        SourceEntity(source, entityId, displayName, canonicalUrl, kind)
}
