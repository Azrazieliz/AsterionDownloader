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
    private val order=listOf("VO","SB","LU","VI","DD","JK")
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
                if(encoded.length !in 36..600)continue
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
            kind !in setOf("e","v","s"))return null
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
