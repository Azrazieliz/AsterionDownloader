package com.asterion.videoflow

import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import java.net.URI
import java.util.Locale

/**
 * Actual public selected Pornhub watch (2026-11-17):
 * /view_video.php?viewkey=<selected> contains exactly one JSON assignment
 * flashvars_<opaque-ID> = { mediaDefinitions:[ {format:"hls",
 * videoUrl:"https://em-h.phncdn.com/...m3u8?<signed>",quality:"1080"}, ...] }.
 *
 * The old generic #player video[src] selector ignores the publisher's
 * selected, signed HLS4 renditions; video.currentSrc is initially absent.
 * Parse ONLY the one selected watch's FIRST-PARTY flashvars config. No web
 * search, related-playlist, advertisement, arbitrary CDN or hardcoded token.
 *
 * A selected HLS playlist is NOT a successful full video/MediaStore receipt.
 */
internal object PornhubOriginalSource {
    data class Quality(val height:Int,val url:String)
    private val assignment=Regex(
        """\b(?:var\s+)?flashvars_[A-Za-z0-9_]{1,72}\s*=\s*""")

    private fun selectedWatch(selected:String):Boolean {
        val uri=runCatching{URI(selected)}.getOrNull()?:return false
        if(uri.scheme!="https" || uri.userInfo!=null || uri.port!=-1 ||
            uri.fragment!=null || uri.path!="/view_video.php")return false
        val host=uri.host.orEmpty().lowercase(Locale.ROOT)
        if(host !in setOf("pornhub.com","www.pornhub.com","m.pornhub.com"))
            return false
        val query=uri.rawQuery.orEmpty()
        // Exactly one selected viewkey; never accept a catalog/recommendation
        // URL or ambiguous duplicate viewkey in the same GET.
        val viewkeys=query.split('&').filter{it.startsWith("viewkey=")}
        return viewkeys.size==1 &&
            viewkeys.single().removePrefix("viewkey=")
                .matches(Regex("[A-Za-z0-9_-]{5,50}"))
    }
    private fun approvedHls(url:String):Boolean {
        val uri=runCatching{URI(url)}.getOrNull()?:return false
        if(uri.scheme!="https" || uri.port!=-1 ||
            uri.userInfo!=null || uri.fragment!=null)return false
        val h=uri.host.orEmpty().lowercase(Locale.ROOT)
        // Real selected 2026-11-17 watch publishes hm-h.phncdn.com,
        // not only the historically observed em-h host. Permit ONLY these
        // exact publisher CDN hosts, never arbitrary *.phncdn.com.
        if(h !in setOf("em-h.phncdn.com","hm-h.phncdn.com"))return false
        return uri.path.orEmpty().matches(
            Regex("""(?i)^/[A-Za-z0-9_./\-]{12,700}\.m3u8$""")) &&
            !FullLengthVideoPolicy.isExplicitPreview("",url)
    }
    /** The same exact selected watch/CDN conditions that authorize real
     * publisher HLS also scope its proven PC video+audio mux settings.
     * Never apply this transfer policy to unrelated pages or CDNs. */
    fun selectedHlsTransfer(selectedWatchUrl:String,manifestUrl:String):Boolean =
        selectedWatch(selectedWatchUrl) && approvedHls(manifestUrl)

    fun published(selected:String,html:String):List<Quality> {
        if(!selectedWatch(selected) || html.length !in 200..2_000_000)
            return emptyList()
        val page=Jsoup.parse(html,selected)
        val configs=ArrayList<JSONObject>()
        for(script in page.select("script:not([src])").take(125)){
            val raw=script.data().ifBlank{script.html()}
            if(!raw.contains("mediaDefinitions") ||
                !raw.contains("flashvars_") || raw.length>1_000_000)continue
            for(match in assignment.findAll(raw).take(4)) {
                val parsed=runCatching {
                    JSONTokener(raw.substring(match.range.last+1))
                        .nextValue() as? JSONObject
                }.getOrNull()?:continue
                if(parsed.optJSONArray("mediaDefinitions")!=null)
                    configs.add(parsed)
            }
        }
        // Multiple independent configurations can represent another scene
        // or an advertisement. This is NOT permission to choose one.
        if(configs.size!=1)return emptyList()
        val player=configs.single()
        if(player.optString("video_unavailable","false")
                .equals("true",ignoreCase=true))return emptyList()
        val media=player.optJSONArray("mediaDefinitions")?:return emptyList()
        val ordered=LinkedHashMap<String,Quality>()
        for(i in 0 until minOf(media.length(),24)) {
            val entry=media.optJSONObject(i)?:continue
            // The selected publisher now labels some authentic master HLS entries
            // hls_master instead of hls. These still require the exact
            // selected flashvars player and the same tightly allowed CDN.
            if(entry.optString("format").lowercase(Locale.ROOT)
                    !in setOf("hls","hls_master"))continue
            val height=entry.optString("quality").toIntOrNull()?:continue
            if(height !in 240..2160)continue
            val url=entry.optString("videoUrl").trim()
            if(!approvedHls(url))continue
            val existing=ordered[url]
            if(existing==null || height>existing.height)
                ordered[url]=Quality(height,url)
        }
        return ordered.values.sortedByDescending{it.height}
    }
}
