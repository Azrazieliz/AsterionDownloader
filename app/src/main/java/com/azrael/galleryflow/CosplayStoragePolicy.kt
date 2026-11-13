package com.azrael.galleryflow

import java.net.URI
import java.util.Locale

/** Organize newly downloaded albums by actual site/creator/date/album.
 * The MediaStore URI remains the authoritative reference for old flat files,
 * which are never moved or removed by this change.
 */
internal object CosplayStoragePolicy {
    private fun segment(raw:String?,fallback:String,max:Int=62):String {
        val text=raw.orEmpty()
            .replace(Regex("""[\\/:*?"<>|\x00-\x1f]"""),"_")
            .replace(Regex("""\s+""")," ")
            .trim(' ','.').take(max)
        return text.ifBlank{fallback}
    }
    private fun date(value:String?):String {
        val match=Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""")
            .find(value.orEmpty())?:return "Undated"
        val year=match.groupValues[1]
        val month=match.groupValues[2].padStart(2,'0')
        val day=match.groupValues[3].padStart(2,'0')
        return if(month.toIntOrNull() in 1..12 &&
            day.toIntOrNull() in 1..31) "$year-$month-$day" else "Undated"
    }
    fun relativePath(entity:EntityRecord,gallery:GalleryMeta):String {
        val site=runCatching{
            URI(entity.canonicalUrl).host.orEmpty().lowercase(Locale.ROOT)
                .removePrefix("www.")
        }.getOrDefault("")
        val artist=gallery.creator
            ?:entity.displayName.takeIf{entity.kind=="tag"}
        val name=segment(gallery.title,"Album",72)
        val suffix=segment(gallery.stableId,"album",10)
        return "Download/AsterionDownloader/Cosplay/"+
            segment(site,"Unknown site")+"/"+segment(artist,"Unknown creator")+
            "/"+date(gallery.publishedAt)+"_"+name+"_"+suffix+"/"
    }
}
