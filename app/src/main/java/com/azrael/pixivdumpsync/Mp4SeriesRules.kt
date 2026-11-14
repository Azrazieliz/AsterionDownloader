package com.azrael.pixivdumpsync

import org.jsoup.nodes.Document
import java.net.URI
import java.text.Normalizer
import java.util.Locale

/** Exact series matching and evidence extraction for MP4Directs' forum layout.
 * No guessed volume numbers, no PDF fallback and no download based on a thread
 * title alone. Direct links still require the user's legitimate site session.
 */
data class Mp4SeriesLink(val url:String,val title:String,val volume:Int?,
    val isEpub:Boolean,val isThread:Boolean,val isImageZip:Boolean=false)

object Mp4SeriesRules {
    private val volumePattern=Regex("""(?i)\b(?:volume|vol\.?|v)\s*[-#:]*\s*0*(\d{1,3})\b""")
    private val common=Regex("""(?i)\b(?:light novel|novel|epub|pdf|download|all volumes|ln|manga|web novel)\b""")
    fun normalize(raw:String):String=Normalizer.normalize(raw,Normalizer.Form.NFKD)
        .replace(Regex("""\p{M}+"""),"")
        .lowercase(Locale.ROOT).replace("&"," and ")
        .replace(Regex("""[^a-z0-9]+""")," ").trim()
        .replace(Regex("""\s+""")," ")
    fun aliases(name:String,extra:String):List<String> {
        val raw=(listOf(name)+extra.split('|',',','\n')).toMutableList()
        // Well-established alternate names for the user's Tensura example.
        if(normalize(name) in setOf("tensura","tensei shitara slime datta ken",
                "that time i got reincarnated as a slime")){
            raw+="Tensura"
            raw+="Tensei Shitara Slime Datta Ken"
            raw+="That Time I Got Reincarnated as a Slime"
        }
        return raw.map(::normalize).filter{it.length>=3}.distinct()
    }
    fun matches(title:String,name:String,extra:String=""):Boolean {
        val haystack=" "+normalize(common.replace(title," "))+" "
        return aliases(name,extra).any{needle->
            haystack.contains(" "+needle+" ")
        }
    }
    fun volume(text:String):Int?=volumePattern.find(text)
        ?.groupValues?.getOrNull(1)?.toIntOrNull()
    fun epub(text:String,url:String):Boolean {
        val path=runCatching{URI(url).path.orEmpty()}.getOrDefault("")
        return path.endsWith(".epub",true) ||
            text.trim().endsWith(".epub",true)
    }
    fun forumLinks(doc:Document,source:String,name:String,extra:String=""):
            List<Mp4SeriesLink>{
        val root=URI(source)
        val found=LinkedHashMap<String,Mp4SeriesLink>()
        for(a in doc.select("a[href]").take(18000)){
            val url=runCatching{root.resolve(a.attr("href"))}.getOrNull()?:continue
            if(url.scheme!="https"||url.userInfo!=null)continue
            val hostname=url.host?.lowercase(Locale.ROOT)?.removePrefix("www.")
                ?:continue
            if(hostname!="mp4directs.com")continue
            val label=(a.attr("title").ifBlank{a.text()}).trim().take(240)
            val path=url.path.orEmpty()
            val thread=path.startsWith("/threads/") ||
                path.startsWith("/forums/")
            val directEpub=epub(label,url.toString())
            if(!thread&&!directEpub)continue
            if(!matches(label,name,extra))continue
            val clean=URI(url.scheme,url.authority,url.path,url.query,null).toString()
            found.putIfAbsent(clean,Mp4SeriesLink(clean,label,volume(label),
                directEpub,thread))
        }
        return found.values.toList()
    }
    fun threadOriginals(doc:Document,source:String,format:String):List<Mp4SeriesLink>{
        val root=URI(source)
        val files=LinkedHashMap<String,Mp4SeriesLink>()
        val ext=if(format=="ZIP")".zip" else ".epub"
        for(a in doc.select("article a[href],.message-body a[href],.bbWrapper a[href]").take(4000)){
            val uri=runCatching{root.resolve(a.attr("href"))}.getOrNull()?:continue
            if(uri.scheme!="https" || uri.userInfo!=null || uri.host.isNullOrBlank())continue
            val label=a.text().trim().take(200)
            val name=a.attr("download").ifBlank{uri.path.substringAfterLast('/')}
            if(!name.endsWith(ext,true))continue
            val nearby=a.parent()?.text().orEmpty().take(200)
            val n=volume(label)?:volume(nearby)?:volume(name)
            val clean=URI(uri.scheme,uri.authority,uri.path,uri.query,null).toString()
            files.putIfAbsent(clean,Mp4SeriesLink(clean,name,n,
                format=="EPUB",false,format=="ZIP"))
        }
        return files.values.toList()
    }
    fun relatedNames(doc:Document,family:String,extra:String=""):List<String>{
        val parents=aliases(family,extra)
        return doc.select("a[href]").take(15000).mapNotNull{a->
            val text=a.attr("title").ifBlank{a.text()}.trim().take(180)
            val name=normalize(text)
            if(name.length<6 || !parents.any{
                parent->name.startsWith(parent+" ") && name!=parent
            })null else text
        }.distinct().take(40)
    }
    /** Volume floor is per work. Side stories have their own independent floor. */
    fun eligible(volume:Int?,firstMissingVolume:Int):Boolean =
        volume!=null && volume>=firstMissingVolume.coerceAtLeast(1)
}
