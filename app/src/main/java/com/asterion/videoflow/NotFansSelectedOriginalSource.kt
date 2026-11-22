package com.asterion.videoflow

import org.jsoup.Jsoup
import java.net.URI
import java.util.Locale

/**
 * NotFans exposes a publisher-owned MP4 download anchor on each /videos/<id>/...
 * watch page. The actual site labels it "Download: MP4 720p, 90.48 Mb".
 * Do not rely on an absent HTML5 <video> source or a generic site extractor.
 *
 * Never use global/related download links: the numeric video ID must match
 * BOTH the selected article and the last two segments of the first-party
 * /get_file/ path. The downloaded response still has to pass NativeMediaDownloader
 * MIME, byte-range, full-length and MediaStore receipt checks.
 */
internal object NotFansSelectedOriginalSource {
    private const val HOST="notfans.com"
    private val videoPath=Regex(
        """(?i)^/videos/([0-9]{1,12})/[a-z0-9][a-z0-9_-]{0,180}/?$""")
    private val filePath=Regex(
        """(?i)^/get_file/[0-9]{1,6}/[a-f0-9]{20,100}/[0-9]{1,12}/([0-9]{1,12})/([0-9]{1,12})\.mp4/?$""")
    private val quality=Regex("""(?i)\bMP4\s+(\d{3,4})p\b""")

    private fun uri(value:String):URI?=runCatching{URI(value)}.getOrNull()
        ?.takeIf{it.scheme=="https" && it.userInfo==null && it.port==-1 &&
            it.rawFragment==null &&
            it.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")==HOST}

    fun selectedId(selected:String):String? {
        val page=uri(selected)?:return null
        if(!page.rawQuery.isNullOrBlank())return null
        return videoPath.matchEntire(page.path.orEmpty())?.groupValues?.get(1)
    }

    fun verifiedDownload(selected:String,media:String):Boolean {
        val selectedId=selectedId(selected)?:return false
        val file=uri(media)?:return false
        val match=filePath.matchEntire(file.path.orEmpty())?:return false
        if(match.groupValues[1]!=selectedId ||
            match.groupValues[2]!=selectedId)return false
        // The documented public download action explicitly marks the file
        // instead of an inline player route. No arbitrary get_file links.
        val params=file.rawQuery?.split('&').orEmpty()
        return params.count{it.equals("download=true",ignoreCase=true)}==1
    }

    fun published(selected:String,html:String):List<String> {
        if(selectedId(selected)==null)return emptyList()
        val doc=Jsoup.parse(html,selected)
        val options=doc.select("a[href]").asSequence().filter{element->
            element.closest("aside,.related,.recommended,.recommendations,"+
                ".ads,.advertisement,.sidebar,[data-ad]")==null
        }.mapNotNull{anchor->
            val height=quality.find(anchor.text())?.groupValues?.get(1)
                ?.toIntOrNull()?.takeIf{it in 240..4320}?:return@mapNotNull null
            val link=anchor.absUrl("href")
            if(verifiedDownload(selected,link))link to height else null
        }.distinct().toList()
        val best=options.maxOfOrNull{it.second}?:return emptyList()
        val winners=options.filter{it.second==best}.map{it.first}.distinct()
        return winners.singleOrNull()?.let(::listOf).orEmpty()
    }
}
