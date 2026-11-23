package com.azrael.galleryflow

/**
 * Source-owned CosplayTele video receipts only. The publisher can advertise
 * video files in addition to its images. A complete PHOTO subset is never
 * proof that the separately advertised VIDEO subset was saved.
 */
internal object CosplayTeleVideoCompleteness {
    data class Audit(val expectedVideos:Int,val verifiedVideos:Int,val complete:Boolean)

    /** Only an exact owner selection may queue its missing selected video.
     * The caller audits actual on-disk video receipts AFTER native downloads.
     * A published archive LINK is not a successful archive transfer: if its
     * videos are still missing, the same selected article may safely retry
     * through the existing VideoFlow engine. Deduplication stays in VideoDb.
     * Never queue videos on unrelated site-wide history enumeration.
     */
    @Suppress("UNUSED_PARAMETER")
    fun queueVideoFallback(exactSelection:Boolean,audit:Audit?,
                           archiveAvailable:Boolean,
                           manuallyRequestedSite:Boolean=false):Boolean =
        // A selected album or explicitly pressed site CHECK NEW/BACKFILL
        // can request its *published, exact article* video. The previous
        // exactSelection-only condition abandoned all site-level video
        // originals while displaying the photo subset as partially saved.
        // Untargeted scheduled scans still cannot enqueue videos.
        (exactSelection || manuallyRequestedSite) &&
            audit!=null && !audit.complete && audit.expectedVideos>0

    /** Legacy single-album entities are explicit selections only for an
     * owner-targeted row. Automatic site backfill must not rearm a cancelled
     * album or enqueue a separate video transfer.
     */
    fun exactEntitySelection(targetEntityId:String?,entityId:String,kind:String):Boolean =
        targetEntityId!=null && targetEntityId==entityId &&
            (kind=="selected_album" || kind=="gallery_only")

    fun audit(expected:Int?,selected:List<MediaRef>,
              records:List<MediaRecord>,onDisk:Set<String>):Audit {
        val selectedVideos=selected.asSequence()
            .filter{it.kind==MediaKind.VIDEO}
            .map{it.stableId}.toSet()
        // A video original is mandatory once the selected article exposes
        // it, even when the publisher omitted its numeric video count.
        // Never allow a lower publisher count to hide other selected files.
        val required=maxOf(expected?.coerceAtLeast(0) ?: 0,selectedVideos.size)
        if(required==0)return Audit(0,0,true)
        val archivePrefixes=selected.asSequence().filter{
            it.kind==MediaKind.ARCHIVE
        }.map{"archive:"+it.normalizedUrl+"#"}.toList()
        val videoExtension=Regex("""(?i)\.(?:mp4|m4v|mkv|webm|mov|avi|ts)$""")
        // The DB may retain duplicate receipt rows; one media identity
        // counts as one completed original, not multiple saved videos.
        val verifiedIds=records.asSequence().filter{record->
            record.mediaId in onDisk && record.state==TransferState.COMPLETE &&
                (record.mediaId in selectedVideos ||
                    archivePrefixes.any{prefix->
                        record.url.startsWith(prefix) &&
                            videoExtension.containsMatchIn(record.filename.orEmpty())
                    })
        }.map{it.mediaId}.toSet()
        val complete=selectedVideos.all{it in verifiedIds} &&
            verifiedIds.size>=required
        return Audit(required,verifiedIds.size,complete)
    }

}
