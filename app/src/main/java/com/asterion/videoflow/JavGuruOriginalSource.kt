package com.asterion.videoflow

import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/**
 * JAV Guru's selected WordPress post contains STREAM SB/VO/LU/VI/DD/JK
 * shortcode controls. Their data-localize keys refer to an inline JSON object
 * whose iframe_url is Base64 of a FIRST-PARTY /searcho/ player URL.
 *
 * The resulting /searcho/ page deliberately starts with about:blank; its own
 * published JS concatenates three attributes, reverses the token, and
 * navigates the exact selected iframe to another first-party URL, which
 * redirects to the hoster's actual embedded player.
 *
 * Decode ONLY the selected article's buttons; never the site's ad iframe,
 * recommendation cards, footer or generic preview/video metadata.
 */
internal object JavGuruOriginalSource {
    data class Player(val server:String,val url:String)
    private val order=listOf("LU","VO","SB","VI","DD","JK")
    private val articlePath=Regex("""^/([0-9]{4,10})/[a-z0-9][a-z0-9_-]{3,240}/?$""",
        RegexOption.IGNORE_CASE)
    private val shortcode=Regex("""(?s)\bvar\s+([a-z]{10})\s*=\s*(\{.*?\})\s*;""")
    private val keyPattern=Regex("""^[a-z]{10}$""")
    private fun safe(raw:String):URI?=runCatching{URI(raw)}.getOrNull()?.takeIf{
        it.scheme=="https" && it.host!=null && it.userInfo==null && it.port==-1
    }
    private fun firstParty(raw:String):Boolean {
        val uri=safe(raw)?:return false
        return uri.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")=="jav.guru" &&
            uri.path=="/searcho/" && uri.rawQuery.orEmpty().length in 16..800
    }
    fun published(selected:String,html:String):List<Player>{
        val uri=safe(selected)?:return emptyList()
        if(uri.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")!="jav.guru")
            return emptyList()
        val post=articlePath.matchEntire(uri.path.orEmpty())?.groupValues?.get(1)
            ?:return emptyList()
        val doc=Jsoup.parse(html,selected)
        val article=doc.selectFirst("article#post-"+post)?:return emptyList()
        val keyToServer=LinkedHashMap<String,String>()
        for(button in article.select("a.wp-btn-iframe__shortcode[data-localize]").take(18)){
            val key=button.attr("data-localize").trim()
            val server=Regex("""^STREAM\s+(SB|VO|LU|VI|DD|JK)$""")
                .matchEntire(button.text().trim().uppercase(Locale.ROOT))
                ?.groupValues?.get(1)?:continue
            if(keyPattern.matches(key))keyToServer[key]=server
        }
        if(keyToServer.isEmpty())return emptyList()
        val found=LinkedHashMap<String,Player>()
        for(script in doc.select("script:not([src])").take(80)){
            val source=script.data().ifBlank{script.html()}
            if(!source.contains("iframe_url"))continue
            for(match in shortcode.findAll(source)){
                val key=match.groupValues[1]
                val server=keyToServer[key]?:continue
                val encoded=runCatching{
                    JSONObject(match.groupValues[2]).optString("iframe_url")
                }.getOrDefault("")
                // The publisher's optional percent-encoded background can
                // make a legitimate Base64 wrapper exceed 600 characters.
                // Its decoded first-party host, route and query are still
                // strictly checked below; this is not an open iframe route.
                if(encoded.length !in 36..1150)continue
                val decoded=runCatching{
                    String(Base64.getDecoder().decode(encoded),
                        StandardCharsets.UTF_8)
                }.getOrNull()?:continue
                if(!firstParty(decoded))continue
                val q=safe(decoded)?.rawQuery.orEmpty()
                // First-party source selector plus optional poster background.
                if(!Regex("""^[a-z]{2}=[a-fA-F0-9]{10,150}(?:&bg=https?%3A%2F%2F[^&]{1,400})?$""")
                    .matches(q))continue
                found.putIfAbsent(server,Player(server,decoded))
            }
        }
        return order.mapNotNull{found[it]}
    }

    /** The first-party wrapper's own JS computes iframe.src using the exact
     * config's cid, three data-* attributes and reversed concatenated token.
     * No network, JS evaluation, invented URL or arbitrary iframe navigation.
     */
    fun nextIframe(wrapperUrl:String,html:String):String?{
        if(!firstParty(wrapperUrl))return null
        val doc=Jsoup.parse(html,wrapperUrl)
        val js=doc.select("script:not([src])").asSequence()
            .map{it.data().ifBlank{it.html()}}
            .firstOrNull{it.contains("function activate_real_stream()") &&
                it.contains("window.cfg") && it.contains("fullToken.rev()")}
            ?:return null
        fun property(name:String):String?=Regex(
            """\b"""+Regex.escape(name)+"""'\s*:\s*['"]([^'"]{1,160})['"]"""
        ).find(js)?.groupValues?.get(1)
            ?:Regex("""\b"""+Regex.escape(name)+"""\s*:\s*['"]([^'"]{1,160})['"]""")
                .find(js)?.groupValues?.get(1)
        val cid=property("cid")?:return null
        val base=property("base")?:return null
        val kind=property("rtype")?:return null
        if(!cid.matches(Regex("""[A-Za-z0-9]{3,32}""")) ||
            base!="https://jav.guru/searcho/" ||
            kind !in setOf("e","v","s","x","t","h","o"))return null
        val list=Regex("""\bkeys\s*:\s*\[\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]+)['"]\s*\]""")
            .find(js)?:return null
        val wrapper=doc.getElementById(cid)?:return null
        val parts=(1..3).map{index->
            val key=list.groupValues[index]
            if(!key.matches(Regex("""data-[a-z0-9]{3,20}""")))return null
            wrapper.attr(key).takeIf{it.matches(Regex("""[a-zA-Z0-9]{5,55}"""))}
                ?:return null
        }
        val token=parts.joinToString("").reversed()
        if(token.length !in 15..120)return null
        val target=base+"?"+kind+"r="+token
        return target.takeIf(::firstParty)
    }

    /** LuluStream's currently published selected player uses a Dean
     * Edwards P.A.C.K.E.R literal for JWPlayer's sources. Decode text only,
     * with strict size/time bounds and without executing external JS.
     *
     * The HLS URL is short-lived: resolve it anew on every selected request.
     * A media link from another script, a recommendation or an ad is rejected.
     */
    /** Selected original HLS from exactly the publisher-selected external player.
     * LU uses Lulu's packed JWPlayer source. The owner's actual MXGS-1452 post
     * has SB/VO/DD/JK only; its selected SB embed resolves to javclan.com's
     * packed, explicitly keyed HLS URLs. Do not require non-existent LU.
     */
    /** Exact selected SB player: packed JWPlayer `links.hls4||links.hls3||links.hls2`.
     * Actual owner MXGS-1452 /e/ embed is javclan.com; hls2 is a
     * publisher-authored, signed *.premilkyway.com /hls2/.../master.m3u8.
     * Other links in that same player are adverts or non-HLS .txt endpoints.
     */
    private fun selectedSbHls(embed:String,html:String):String? {
        if(!selectedEmbed("SB",embed) ||
            safe(embed)?.host.orEmpty().lowercase(Locale.ROOT)
                .removePrefix("www.")!="javclan.com")return null
        val page=Jsoup.parse(html,embed)
        val found=LinkedHashSet<String>()
        for(script in page.select("script:not([src])").take(60)){
            val raw=script.data().ifBlank{script.html()}
            if(!raw.contains("eval(function(p,a,c,k,e,d)"))continue
            val decoded=RoshyPackedOriginalSource.unpack(raw)?:continue
            if(!Regex("""(?s)jwplayer\(["']vplayer["']\)\.setup\(""")
                    .containsMatchIn(decoded) ||
                !Regex("""(?s)\bsources\s*:\s*\[\s*\{\s*file\s*:\s*links\.hls4\s*\|\|\s*links\.hls3\s*\|\|\s*links\.hls2""")
                    .containsMatchIn(decoded))continue
            val assignments=Regex("""\bvar\s+links\s*=\s*""")
                .findAll(decoded).take(2).toList()
            if(assignments.size!=1)continue
            val obj=runCatching {
                org.json.JSONTokener(decoded.substring(
                    assignments.single().range.last+1)).nextValue()
                    as? JSONObject
            }.getOrNull()?:continue
            // A higher-priority hls3 .txt is not a playable HLS manifest;
            // use only the signed hls2 original published by this same
            // selected JWPlayer. Never turn .txt or ad URLs into media.
            val link=obj.optString("hls2").trim()
            val uri=safe(link)?:continue
            val host=uri.host.orEmpty().lowercase(Locale.ROOT)
            if(!host.endsWith(".premilkyway.com") ||
                uri.fragment!=null || uri.path.orEmpty().contains("..") ||
                !uri.path.orEmpty().matches(
                    Regex("""(?i)^/hls2/[A-Za-z0-9_,./-]{20,650}/master\.m3u8$""")) ||
                uri.rawQuery.isNullOrBlank())continue
            if(FullLengthVideoPolicy.isExplicitPreview("",link))continue
            found+=link
        }
        return found.singleOrNull()
    }

    /** The owner's MXGS-1452 STREAM JK first-party shortcode resolves to
     * one Maxstream embed. Its P.A.C.K.E.R has exactly one published
     * `file: https://s4.maxstream.org/...m3u8?...` HLS source. This is
     * unrelated to the prematurely changing SB playlist and must remain
     * tied to the exact selected JK embed, never ad or recommendation JS.
     * This parser does not claim whole-file completion; the subsequent
     * transfer must verify all VOD fragments and duration.
     */
    private fun selectedJkHls(embed:String,html:String):String? {
        if(!selectedEmbed("JK",embed) ||
            safe(embed)?.host.orEmpty().lowercase(Locale.ROOT)
                .removePrefix("www.")!="maxstream.org")return null
        if(html.length !in 200..1_000_000)return null
        val page=Jsoup.parse(html,embed)
        val found=LinkedHashSet<String>()
        for(script in page.select("script:not([src])").take(60)){
            val raw=script.data().ifBlank{script.html()}
            if(!raw.contains("eval(function(p,a,c,k,e,d)"))continue
            val decoded=RoshyPackedOriginalSource.unpack(raw)?:continue
            val sources=Regex(
                """(?i)\bfile\s*:\s*["'](https://[^"'<>\\\s]{12,2200})["']"""
            ).findAll(decoded).map{it.groupValues[1].replace("\\/","/")}
                .distinct().take(4).toList()
            for(source in sources){
                val uri=safe(source)?:continue
                if(uri.host.orEmpty().lowercase(Locale.ROOT)!="s4.maxstream.org" ||
                    uri.fragment!=null || uri.path.isNullOrBlank() ||
                    uri.path!!.length !in 12..900 ||
                    !uri.path!!.endsWith(".m3u8",ignoreCase=true) ||
                    uri.path!!.contains("..") ||
                    uri.rawQuery.isNullOrBlank() ||
                    FullLengthVideoPolicy.isExplicitPreview("",source))continue
                found.add(source)
            }
        }
        return found.singleOrNull()
    }

    /** Selected original HLS from the exact publisher-selected external player.
     * LU uses the original LuluStream contract. The owner's actual selected SB
     * source is handled independently without weakening LU's source identity.
     */
    fun selectedHls(embed:String,html:String):String? {
        val h=safe(embed)?.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        if(h=="javclan.com")return selectedSbHls(embed,html)
        if(h=="maxstream.org")return selectedJkHls(embed,html)
        if(!selectedEmbed("LU",embed) ||
            h !in setOf("streamhihi.com","lulustream.com"))return null
        if(html.length !in 250..1_000_000)return null
        val document=Jsoup.parse(html,embed)
        val verified=LinkedHashSet<String>()
        for(script in document.select("script:not([src])").take(60)){
            val body=script.data().ifBlank{script.html()}
            if(!body.contains("eval(function(p,a,c,k,e,d)"))continue
            val decoded=RoshyPackedOriginalSource.unpack(body)?:continue
            if(!decoded.contains("jwplayer") || !decoded.contains("sources:"))
                continue
            val sourceRegex=Regex(
                """(?is)\bsources\s*:\s*\[\s*\{\s*file\s*:\s*["'](https://[^"'<>\\\s]{12,2500})["']"""
            )
            val candidates=sourceRegex.findAll(decoded)
                .map{it.groupValues[1].replace("\\/","/")}
                .distinct().take(3).toList()
            if(candidates.size!=1)continue
            val candidate=candidates.single()
            val uri=safe(candidate)?:continue
            val host=uri.host.orEmpty().lowercase(Locale.ROOT)
            if(!(host=="tnmr.org" || host.endsWith(".tnmr.org") ||
                host=="lulucdn.com" || host.endsWith(".lulucdn.com")))continue
            if(!uri.path.orEmpty().matches(
                    Regex("""(?i)^/hls[0-9]*/[a-zA-Z0-9_./-]{5,200}/master\.m3u8$""")))
                continue
            if(uri.rawQuery.isNullOrBlank())continue
            verified.add(candidate)
        }
        return verified.singleOrNull()
    }

    /** Selected player redirects must resolve to actual video embeds, not
     * publisher ads or generic pages on the watch site's own domain.
     * The server is tied to a selected shortcode, not a guessed host. */
    fun selectedEmbed(server:String,raw:String):Boolean {
        if(server !in order)return false
        val uri=safe(raw)?:return false
        val host=uri.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        if(host=="jav.guru" || host.endsWith(".jav.guru"))return false
        if(host in setOf("go.mayzaent.com","a.pemsrv.com","popcash.net",
                "clickadu.com","doubleclick.net","googleads.g.doubleclick.net"))
            return false
        return Regex("""(?i)^/(?:e|embed|v|video|d)/[A-Za-z0-9_-]{5,180}/?$""")
            .matches(uri.path.orEmpty())
    }
}
