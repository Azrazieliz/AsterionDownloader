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

    private val http = HttpClient(context)

    fun run(mode: SyncMode, targetEntityId: String? = null,
            targetSiteHost: String? = null,
            targetSiteSource: String? = null,
            targetGalleryUrl: String? = null,
            targetGalleryTitle: String? = null,
            progress: (String) -> Unit = {}): Stats {
        com.azrael.pixivdumpsync.BackfillPerformance.backgroundWorker()
        val stats = Stats()
        if (!SyncControl.tryStart(mode, queueLiveIfBusy =
            targetEntityId==null && targetSiteHost==null && targetGalleryUrl==null)) {
            // A targeted Kyora request must not be marked "completed" when
            // another sync owns the global lock. Its durable request ledger
            // will retry it after the active run. Never turn a busy worker
            // into an invisible zero-gallery success.
            stats.errors++
            progress("Gallery engine busy; targeted request retained for retry")
            return stats
        }
        val db = GalleryDb(context)
        val files = MediaStoreFiles(context, http)
        val flow = FlowLinkEmitter(context, db)
        try {
            SyncControl.setMode(mode)
            if(targetGalleryUrl!=null){
                require(targetEntityId!=null){"Selected gallery must belong to a registered site"}
                runExactAlbum(db,files,flow,stats,progress,targetEntityId,
                    targetGalleryUrl,targetGalleryTitle,targetSiteHost,targetSiteSource)
            }else {
                // Legacy manual site:all requests without their owning host
                // must never fan out across every native gallery provider.
                require(targetEntityId!="site:all" || targetSiteHost!=null){
                    "Manual site check needs its provider identity; select CHECK NEW on that site"
                }
                when (mode) {
                SyncMode.LIVE -> runLive(db, files, flow, stats, progress,
                    targetEntityId,targetSiteHost,targetSiteSource)
                SyncMode.BACKFILL -> runBackfill(db, files, flow, stats, progress,
                    targetEntityId,targetSiteHost,targetSiteSource)
                }
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

    /** Single tapped album, owned by its existing publisher site record.
     * No gallery URL ever becomes a new followed source/subscription.
     */
    private fun runExactAlbum(db:GalleryDb, files:MediaStoreFiles,
                              flow:FlowLinkEmitter,stats:Stats,
                              progress:(String)->Unit,entityId:String,
                              rawUrl:String,title:String?,siteHost:String?,
                              siteSource:String?){
        val entity=KyoraSelectedGalleryIdentity.parent(db.listEntities(),
            entityId,rawUrl,siteHost,siteSource) ?:throw AdapterException(
                "Selected album's exact parent provider is not registered")
        // Preserve Kyora's original native stable IDs. The unified
        // selection path formerly synthesized a generic hash for every
        // non-Kiutaku album; BuonDua then looked for /photos/<hash>/
        // instead of /photos/<real-id>/ and found no originals.
        val selected=KyoraSelectedGalleryIdentity.canonicalUrl(
            entity.source,rawUrl)
        require(KyoraSelectedGalleryIdentity.sameApprovedSite(
                entity.canonicalUrl,selected)){
            "The album URL must belong to its own registered provider"
        }
        val id=KyoraSelectedGalleryIdentity.galleryId(entity.source,selected)
        val ref=GalleryRef(entity.source,id,entity.entityId,selected,
            title?.take(160)?.ifBlank{rawUrl}.orEmpty().ifBlank{rawUrl})
        val adapter=AdapterRegistry.forSource(entity.source,context)
        progress("Downloading only selected gallery: "+ref.title)
        if(processGallery(entity,ref,adapter,db,files,flow,stats,progress)){
            db.clearRetry(ref)
        }else{
            db.queueRetry(ref,"Exact selected album incomplete")
            stats.incompleteGalleries++
        }
    }

    private fun runLive(
        db: GalleryDb,
        files: MediaStoreFiles,
        flow: FlowLinkEmitter,
        stats: Stats,
        progress: (String) -> Unit,
        targetEntityId: String? = null,
        targetSiteHost: String? = null,
        targetSiteSource: String? = null
    ) {
        val targeted=targetEntityId!=null || targetSiteHost!=null || targetSiteSource!=null
        val entities = db.listEntities().filter {
            it.selected &&
                (targeted || (it.liveEnabled &&
                    com.azrael.pixivdumpsync.SiteControls.sync(context,
                        it.canonicalUrl,it.liveEnabled))) &&
                (targetEntityId == null || it.entityId == targetEntityId) &&
                (targetSiteHost==null || com.azrael.pixivdumpsync.SiteControls.key(it.canonicalUrl)==targetSiteHost) &&
                (targetSiteSource==null || it.source.wireName==targetSiteSource)
        }
        stats.entities = maxOf(stats.entities, entities.size)
        for ((entityIndex, entity) in entities.withIndex()) {
            if (!SyncControl.checkpoint()) return
            // Manual Check New must work independently from the automatic
            // Sync toggle; scheduled Live runs still require opt-in.
            if(!targeted &&
                !com.azrael.pixivdumpsync.SiteControls.sync(context,entity.canonicalUrl))
                continue
            val adapter = AdapterRegistry.forSource(entity.source,context)
            if (!adapter.enabled) {
                db.setEntityError(entity.source, entity.entityId, adapter.statusLabel)
                continue
            }
            val message = "Live • " + (entityIndex + 1) + "/" + entities.size + " • " + entity.displayName
            SyncControl.updateMessage(message)
            progress(message)
            try {
                // A first manual check gets the newest ONE album rather than
                // silently creating a cursor and downloading nothing. Backfill
                // remains the separate action for the complete site history.
                val galleries = if(entity.liveCursor.isNullOrBlank())
                    // Never crawl a thousand historical pages merely to
                    // establish the baseline, whether automatic or manual.
                    adapter.enumerateGalleries(entity.toSourceEntity()).take(1).toList()
                else adapter.enumerateGalleries(entity.toSourceEntity())
                    .takeWhile{it.stableId!=entity.liveCursor}
                    .take(101).toList()
                if(galleries.size>100)throw AdapterException(
                    "More than 100 new galleries or an expired cursor; use BACKFILL "+
                    "to reconcile history without an unbounded CHECK NEW scan.")
                val newest = galleries.firstOrNull()?.stableId
                if (entity.liveCursor.isNullOrBlank()) {
                    if(targeted && galleries.isNotEmpty()){
                        val latest=galleries.first()
                        if(processGallery(entity,latest,adapter,db,files,flow,stats,progress)){
                            db.setLiveCursor(entity.source,entity.entityId,newest)
                        }else{
                            db.queueRetry(latest,"Initial new-album check incomplete")
                            stats.incompleteGalleries++
                        }
                    }else{
                        db.setLiveCursor(entity.source, entity.entityId, newest)
                        progress(entity.displayName + ": Live baseline established")
                    }
                    continue
                }
                val newOnes = galleries.takeWhile { it.stableId != entity.liveCursor }
                for (gallery in newOnes.asReversed()) {
                    if (!SyncControl.checkpoint()) return
                    if(!targeted &&
                        !com.azrael.pixivdumpsync.SiteControls.sync(context,entity.canonicalUrl))
                        break
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
        targetEntityId: String? = null,
        targetSiteHost: String? = null,
        targetSiteSource: String? = null
    ) {
        // A user-initiated exact gallery or site BACKFILL is an explicit
        // command, independent of the provider's automatic-history switch.
        // The switch still controls untargeted scheduled background passes.
        val targeted = targetEntityId != null || targetSiteHost != null ||
            targetSiteSource != null
        val entities = db.listEntities().filter {
            it.selected &&
                (targeted || com.azrael.pixivdumpsync.SiteControls.backfill(
                    context,it.canonicalUrl,it.selected)) &&
                (targetEntityId == null || it.entityId == targetEntityId) &&
                (targetSiteHost==null || com.azrael.pixivdumpsync.SiteControls.key(it.canonicalUrl)==targetSiteHost) &&
                (targetSiteSource==null || it.source.wireName==targetSiteSource)
        }
        stats.entities = maxOf(stats.entities, entities.size)
        for ((entityIndex, entity) in entities.withIndex()) {
            if (!SyncControl.checkpoint()) return
            if(!targeted &&
                !com.azrael.pixivdumpsync.SiteControls.backfill(context,entity.canonicalUrl))
                continue
            val adapter = AdapterRegistry.forSource(entity.source,context)
            if (!adapter.enabled) {
                db.setEntityError(entity.source, entity.entityId, adapter.statusLabel)
                continue
            }
            val selectedAlbum=entity.kind=="selected_album" ||
                (targetEntityId!=null && entity.kind=="gallery_only")
            val prefix=if(selectedAlbum)
                "Selected album • "+entity.displayName
                else "Backfill • " + (entityIndex + 1) + "/" + entities.size + " • " + entity.displayName
            SyncControl.updateMessage(prefix)
            progress(prefix)
            try {
                val pages = adapter.enumerateGalleries(entity.toSourceEntity())
                var failedCount = 0
                val attemptedGalleries=hashSetOf<String>()
                // Retry a bounded batch of historical failures, then continue onward.
                for (retry in db.pendingRetries(entity)) {
                    if(!attemptedGalleries.add(retry.stableId))continue
                    if (!SyncControl.checkpoint()) return
                    if(!targeted &&
                        !com.azrael.pixivdumpsync.SiteControls.backfill(context,entity.canonicalUrl))
                        break
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
                    if(!attemptedGalleries.add(gallery.stableId))continue
                    if (!SyncControl.checkpoint()) return
                    if(!targeted &&
                        !com.azrael.pixivdumpsync.SiteControls.backfill(context,entity.canonicalUrl))
                        break
                    val message=if(selectedAlbum)
                        "Selected album • "+gallery.title+
                        " • "+stats.mediaDownloaded+" original files received"
                        else entity.displayName + " / " +
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
                    // Do not interrupt an explicitly tapped album with
                    // unrelated Live Sync before its own originals complete.
                    if (!targeted && serviceQueuedLive(db, files, flow, stats, progress)) {
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
            // The original 37-photo Cosplay selection had 54 media rows
            // because recommendation thumbnails were assigned to the album.
            // Reconcile only if the publisher's explicit image count agrees
            // with the exact, scoped original list. Existing files are kept.
            if(SelectedAlbumScope.owns(meta.canonicalUrl)){
                val declared=SelectedAlbumScope.advertisedCount(
                    ref.title,meta.title)
                // A single genuine official archive is validated by
                // expectedArchivePhotos after extraction. A webpage gallery
                // instead MUST match the selected article's claimed count
                // before any media is downloaded. Prior code accepted all
                // 54 including recommendations when the title said 37.
                val officialArchive=mediaItems.size==1 &&
                    mediaItems.first().expectedArchivePhotos!=null
                if(declared!=null && !officialArchive)
                    SelectedAlbumScope.verifyAdvertisedCount(
                        declared,mediaItems.size)
                if(declared!=null && declared==mediaItems.size){
                    val moved=db.detachUnverifiedSelectedAlbumMedia(
                        meta.source,meta.stableId,
                        mediaItems.map{it.stableId}.toSet())
                    if(moved>0)progress(
                        "Separated $moved unrelated saved files from "+
                            meta.title+" without deleting originals")
                }
            }
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
            // Network transfers are independent; persistence and dedup commits
            // are not. Transfer three originals concurrently in bounded
            // batches, then write receipts on this one owner thread.
            // This avoids racing the shared GalleryDb/MediaStore dedup ledger
            // and prevents queues containing thousands of scheduled futures.
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
            val workers = java.util.concurrent.Executors.newFixedThreadPool(3)
            try {
                for (batch in mediaItems.withIndex().chunked(3)) {
                    if (!SyncControl.checkpoint()) return false
                    val scheduled = mutableListOf<Pair<MediaRef,
                        java.util.concurrent.Future<DownloadOutcome>>>()
                    for ((position, media) in batch) {
                        if (!SyncControl.checkpoint()) return false
                        db.upsertMedia(entity.entityId, media)
                        val blockedPost = TagExclusions.firstBlocked(
                            media.tags, Prefs.effectiveExcludedTags(context, entity.entityId))
                        if (blockedPost != null) {
                            val saved = db.media(media.source, media.stableId)
                            if (saved?.state != TransferState.COMPLETE ||
                                !files.exists(saved.contentUri)) {
                                db.setMediaState(media.source, media.stableId,
                                    TransferState.SKIPPED)
                            }
                            continue
                        }
                        val existing = db.media(media.source, media.stableId)
                        // User cancellation persists across backfill and live
                        // rescans. upsertMedia updates metadata, not state.
                        // Never restart a deliberately discarded original.
                        if (existing?.state == TransferState.CANCELLED) {
                            allComplete=false
                            continue
                        }
                        if (existing != null && existing.state == TransferState.COMPLETE &&
                            files.exists(existing.contentUri)) continue
                        if (existing != null && !files.exists(existing.contentUri)) {
                            db.setMediaState(media.source, media.stableId,
                                TransferState.PENDING)
                        }
                        SyncControl.updateMessage(meta.title + " • " +
                            (position + 1) + "/" + mediaItems.size)
                        db.markMediaDownloading(media)
                        scheduled += media to workers.submit<DownloadOutcome> {
                            downloadWithRetry(files, entity, meta, media)
                        }
                    }
                    var rateLimited = false
                    for ((media, future) in scheduled) {
                        try {
                            val outcome = future.get()
                            // The source archive can yield additional genuine
                            // images and videos. Preserve all of its receipts.
                            commitSaved(media, outcome.primary)
                            for (extracted in outcome.extracted) {
                                db.upsertMedia(entity.entityId, extracted.media)
                                commitSaved(extracted.media, extracted.result)
                            }
                        } catch (failure: Throwable) {
                            val cause = if (failure is java.util.concurrent.ExecutionException)
                                failure.cause ?: failure else failure
                            if (cause is VirtualMachineError || cause is ThreadDeath)
                                throw cause
                            if (SyncControl.isStopping() ||
                                cause is SyncCancelledException ||
                                cause is InterruptedException) return false
                            allComplete = false
                            if (cause is HttpStatusException) {
                                val state = when (cause.code) {
                                    401, 403, 404, 410 -> TransferState.INACCESSIBLE
                                    429, 500, 502, 503, 504 -> TransferState.RETRYING
                                    else -> TransferState.PARTIAL
                                }
                                db.setMediaState(media.source, media.stableId, state)
                                if (cause.code == 429) rateLimited = true
                            } else {
                                db.setMediaState(media.source, media.stableId,
                                    TransferState.RETRYING)
                            }
                        }
                    }
                    // Stop scheduling new batches when a publisher rate-limits.
                    // Already-started files retain their individual receipts.
                    if (rateLimited) break
                }
            } finally {
                workers.shutdownNow()
            }

            var officialArchiveComplete=true
            val officialArchive=mediaItems.singleOrNull {
                it.expectedArchivePhotos!=null
            }
            val expected=officialArchive?.expectedArchivePhotos
            if(officialArchive!=null && expected!=null){
                val memberPrefix="archive:"+officialArchive.normalizedUrl+"#"
                val extracted=db.galleryMedia(meta.source,meta.stableId)
                    .filter{record->
                        record.url.startsWith(memberPrefix) &&
                        record.filename.orEmpty().matches(
                            Regex("""(?i)^.*\.(?:jpg|jpeg|png|gif|webp|avif)$""")) &&
                        record.state==TransferState.COMPLETE &&
                        files.exists(record.contentUri)
                    }
                if(extracted.size!=expected){
                    officialArchiveComplete=false
                    progress("4KHD full-HD archive incomplete: "+
                        extracted.size+" extracted photos, expected "+expected)
                }else{
                    // Only a verified original archive of exactly the
                    // advertised size can displace old webpage previews.
                    db.detachUnverifiedSelectedAlbumMedia(
                        meta.source,meta.stableId,
                        db.galleryMedia(meta.source,meta.stableId)
                            .filter { it.url.startsWith(memberPrefix) }
                            .map { it.mediaId }.toSet()+officialArchive.stableId)
                    db.updateGalleryMeta(meta,expected)
                }
            }
            val records = db.galleryMedia(meta.source, meta.stableId)
            val diskComplete = officialArchiveComplete &&
                records.isNotEmpty() &&
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
