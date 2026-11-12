package com.asterion.videoflow

import com.azrael.galleryflow.SiteSession
import org.jsoup.Jsoup
import java.net.URI
import java.util.Locale

/**
 * A direct original must be linked by the selected FreePornVideos video page.
 * Never mistake an ad iframe, another video, a thumbnail, or a preview for
 * the requested original. The transfer engine still validates saved runtime.
 *
 * A missing published runtime does NOT invent one: the ordinary extractor
 * preflight must supply duration before an unattended transfer is admitted.
 */
internal object FreePornVideosOriginalSource {
    data class Original(val mediaUrl:String,val durationSeconds:Long?)

    fun isExactPage(url:String):Boolean=runCatching {
        val u=URI(url)
        u.scheme=="https" && u.userInfo==null && u.port==-1 &&
            u.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")=="freepornvideos.xxx" &&
            Regex("""^/videos/[0-9]{5,12}/[a-zA-Z0-9_-]{2,240}/?$""")
                .matches(u.path.orEmpty())
    }.getOrDefault(false)

    private fun durationSeconds(value:String):Long? {
        val raw=value.trim()
        if(raw.isBlank())return null
        val iso=Regex("""(?i)^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$""")
            .matchEntire(raw)
        val parsed=if(iso!=null){
            (iso.groupValues[1].toLongOrNull()?:0L)*3600L+
                (iso.groupValues[2].toLongOrNull()?:0L)*60L+
                (iso.groupValues[3].toLongOrNull()?:0L)
        }else if(raw.contains(":")){
            val parts=raw.split(':').map{it.toLongOrNull()?:return null}
            if(parts.size !in 2..3 || parts.any{it>59L})return null
            parts.fold(0L){total,part->total*60L+part}
        }else raw.toLongOrNull()?:return null
        return parsed.takeIf{it in 60L..36000L}
    }

    fun parse(html:String,pageUrl:String):Original? {
        if(!isExactPage(pageUrl))return null
        val doc=Jsoup.parse(html,pageUrl)
        val downloadLinks=doc.select("a[href*=/get_file/][href]")
            .mapNotNull{a->runCatching{URI(pageUrl).resolve(a.attr("href"))}.getOrNull()}
            .filter{url->
                url.scheme=="https" && url.userInfo==null && url.port==-1 &&
                    url.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")=="freepornvideos.xxx" &&
                    url.path.orEmpty().startsWith("/get_file/") &&
                    Regex("""(?i)\.mp4/?$""").containsMatchIn(url.path.orEmpty()) &&
                    !url.path.orEmpty().contains("preview",true) &&
                    !url.path.orEmpty().contains("trailer",true) &&
                    !url.path.orEmpty().contains("sample",true)
            }
        if(downloadLinks.isEmpty())return null
        // Only use first-party source links. Prefer the advertised higher
        // resolution when several original encodings exist on the page.
        val media=downloadLinks.maxByOrNull{url->
            val p=url.path.orEmpty()
            when{
                Regex("""(?i)(?:2160|4k)""").containsMatchIn(p)->4
                p.contains("1080")->3
                p.contains("720")->2
                p.contains("480")->1
                else->0
            }
        }?.toString()?:return null
        val durations=doc.select("meta[itemprop=duration],"+
            "meta[property=video:duration],meta[name=duration]")
            .mapNotNull{durationSeconds(it.attr("content"))}
        val jsonDurations=doc.select("script[type=application/ld+json]")
            .flatMap{script->
                Regex(""""duration"\s*:\s*"([^"]+)"""")
                    .findAll(script.data().ifBlank{script.html()})
                    .mapNotNull{durationSeconds(it.groupValues[1])}.toList()
            }
        val candidates=durations+jsonDurations
        // Contradictory page metadata is unsafe; without a trusted number the
        // caller will require the generic media preflight instead.
        val duration=candidates.takeIf{
            it.isNotEmpty() && it.maxOrNull()!!-it.minOrNull()!!<=5L
        }?.first()
        return Original(media,duration)
    }

    fun resolve(pageUrl:String):Original? {
        if(!isExactPage(pageUrl))return null
        val response=Jsoup.connect(pageUrl)
            .userAgent("Mozilla/5.0 (Linux; Android 17; Mobile)")
            .ignoreHttpErrors(true).followRedirects(false)
            .timeout(16_000).maxBodySize(3_000_000)
            .apply{SiteSession.cookie(pageUrl)?.let{header("Cookie",it)}}
            .execute()
        if(response.statusCode() !in 200..299)return null
        if(!isExactPage(response.url().toString()))return null
        return parse(response.body(),pageUrl)
    }
}
