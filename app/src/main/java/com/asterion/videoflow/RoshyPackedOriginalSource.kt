package com.asterion.videoflow

import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import java.net.URI
import java.util.Locale

/**
 * Roshy selected Original Stream -> exact Kamehaus /e/<id> JWPlayer.
 *
 * Kamehaus embeds its source in a Dean Edwards P.A.C.K.E.R inline script;
 * yt-dlp 2026.10.26 returns Unsupported URL for the HTML player, but the
 * unexecuted, unpacked script contains the selected JWPlayer links.hls4
 * (a first-party /stream/.../master.m3u8 playlist) and signed CDN fallback.
 *
 * Never execute a script, scan unrelated /watch or ad players, fabricate a
 * manifest, or turn an HTML iframe into a claimed downloaded original.
 */
internal object RoshyPackedOriginalSource {
    private fun uri(s:String):URI?=runCatching{URI(s)}.getOrNull()
    private fun host(s:String):String=uri(s)?.host.orEmpty().lowercase(Locale.ROOT)
        .removePrefix("www.")
    private val playerPath=Regex("""^/e/([A-Za-z0-9_-]{6,80})/?$""")
    private val firstParty=Regex(
        """^/stream/[A-Za-z0-9_-]{6,80}/[A-Za-z0-9_-]{6,80}/"""+
        """[0-9]{8,12}/[0-9]{5,12}/master\.m3u8$""")
    private val packer=Regex(
        """(?s)eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\).*?\}\s*\(\s*'((?:\\.|[^'\\])*)'\s*,\s*(\d{1,2})\s*,\s*(\d{1,4})\s*,\s*'((?:\\.|[^'\\])*)'\.split\('\|'\)"""
    )
    fun selectedEmbed(selected:String,frame:String):Boolean {
        val owner=uri(selected)?:return false
        val f=uri(frame)?:return false
        return owner.scheme=="https" && f.scheme=="https" &&
            owner.userInfo==null && f.userInfo==null &&
            owner.port==-1 && f.port==-1 &&
            host(selected)=="roshy.tv" && host(frame)=="kamehaus.net" &&
            VideoSiteProfiles.isVideoPage(selected) &&
            playerPath.matches(f.path.orEmpty()) &&
            f.rawQuery.isNullOrBlank() && f.fragment==null
    }
    private fun unpack(raw:String):String? {
        if(raw.length !in 100..160_000)return null
        val match=packer.find(raw)?:return null
        val packed=match.groupValues[1]
        val radix=match.groupValues[2].toIntOrNull()?:return null
        val count=match.groupValues[3].toIntOrNull()?:return null
        val words=match.groupValues[4].split('|')
        if(radix !in 2..36 || count !in 1..850 ||
            packed.length !in 50..120_000 || words.size>900)return null
        val alphabet="0123456789abcdefghijklmnopqrstuvwxyz"
        fun token(number:Int):String {
            var n=number
            if(n==0)return "0"
            val result=StringBuilder()
            while(n>0){
                result.append(alphabet[n%radix])
                n/=radix
            }
            return result.reverse().toString()
        }
        var decoded=packed
        for(index in count-1 downTo 0){
            val word=words.getOrNull(index).orEmpty()
            if(word.isBlank())continue
            decoded=Regex("""\b"""+Regex.escape(token(index))+"""\b""")
                .replace(decoded){word}
            if(decoded.length>180_000)return null
        }
        return decoded
    }
    /** Parsing the unpacked public publisher text separately allows precise
     * regression tests for source attribution and malicious alternate URLs.
     */
    internal fun fromDecoded(selected:String,frame:String,decoded:String):String?{
        if(!selectedEmbed(selected,frame) || decoded.length !in 160..180_000)
            return null
        val id=playerPath.matchEntire(uri(frame)?.path.orEmpty())
            ?.groupValues?.get(1)?:return null
        // The actual player references THIS ID when retaining seek position.
        // A copied advertisement config from another iframe is not selected.
        if(!decoded.contains("tt$id") ||
            !Regex("""(?s)jwplayer\(["']vplayer["']\)\.setup\(""")
                .containsMatchIn(decoded) ||
            !Regex("""sources:\s*\[\s*\{\s*file:\s*links\.hls4\s*\|\|\s*links\.hls3\s*\|\|\s*links\.hls2""")
                .containsMatchIn(decoded))return null
        val objects=Regex("""\bvar\s+links\s*=\s*""").findAll(decoded).take(2).toList()
        if(objects.size!=1)return null
        val json=runCatching {
            JSONTokener(decoded.substring(objects.single().range.last+1))
                .nextValue() as? JSONObject
        }.getOrNull()?:return null
        // Prefer the highest-quality public first-party manifest. The exact
        // selected publisher player, not a generic hoster or ad, supplies it.
        for(name in listOf("hls4","hls2")){
            val raw=json.optString(name).trim()
            if(raw.isBlank())continue
            val link=runCatching{uri(frame)!!.resolve(raw).normalize()}
                .getOrNull()?:continue
            val h=link.host.orEmpty().lowercase(Locale.ROOT)
            if(link.scheme!="https" || link.userInfo!=null ||
                link.port!=-1 || link.fragment!=null)continue
            val path=link.path.orEmpty()
            val valid=if(name=="hls4")
                h=="kamehaus.net" && firstParty.matches(path)
            else
                h.endsWith(".premilkyway.com") &&
                path.startsWith("/hls2/") &&
                path.endsWith(".m3u8") && !link.rawQuery.isNullOrBlank()
            if(valid)return link.toASCIIString()
        }
        return null
    }
    fun selectedPlaylist(selected:String,frame:String,html:String):String? {
        if(!selectedEmbed(selected,frame) || html.length !in 200..1_500_000)
            return null
        val doc=Jsoup.parse(html,frame)
        val found=LinkedHashSet<String>()
        for(script in doc.select("script:not([src])").take(70)){
            val body=script.data().ifBlank{script.html()}
            if(!body.contains("eval(function(p,a,c,k,e,d)"))continue
            val decoded=unpack(body)?:continue
            val manifest=fromDecoded(selected,frame,decoded)?:continue
            found.add(manifest)
        }
        // Reject multiple independent players rather than picking an advert.
        return found.singleOrNull()
    }
}
