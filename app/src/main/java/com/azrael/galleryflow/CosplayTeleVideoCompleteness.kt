package com.azrael.galleryflow

/**
 * Source-owned CosplayTele video receipts only. The publisher can advertise
 * video files in addition to its images. A complete PHOTO subset is never
 * proof that the separately advertised VIDEO subset was saved.
 */
internal object CosplayTeleVideoCompleteness {
    data class Audit(val expectedVideos:Int,val verifiedVideos:Int,val complete:Boolean)

    fun audit(expected:Int?,selected:List<MediaRef>,
              records:List<MediaRecord>,onDisk:Set<String>):Audit {
        val required=expected?.coerceAtLeast(0) ?: 0
        if(required==0)return Audit(0,0,true)
        val selectedVideos=selected.asSequence().filter{
            it.kind==MediaKind.VIDEO
        }.map{it.stableId}.toSet()
        val archivePrefixes=selected.asSequence().filter{
            it.kind==MediaKind.ARCHIVE
        }.map{"archive:"+it.normalizedUrl+"#"}.toList()
        val videoExtension=Regex("""(?i)\.(?:mp4|m4v|mkv|webm|mov|avi|ts)$""")
        val count=records.count{record->
            record.mediaId in onDisk && record.state==TransferState.COMPLETE &&
                (record.mediaId in selectedVideos ||
                    archivePrefixes.any{prefix->
                        record.url.startsWith(prefix) &&
                            videoExtension.containsMatchIn(record.filename.orEmpty())
                    })
        }
        return Audit(required,count,count>=required)
    }
}
