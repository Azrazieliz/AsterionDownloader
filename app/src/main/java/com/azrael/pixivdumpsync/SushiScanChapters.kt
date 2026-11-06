package com.azrael.pixivdumpsync

import org.jsoup.nodes.Document
import java.net.URI
import java.util.Locale

/** Adapter for the user-approved Sushi-Scan catalogue.
 * Series pages live under /catalogue/<slug>/ while reader pages live at
 * /<slug>-chapitre-N/ or /<slug>-volume-N/ at the site root. The generic
 * manga parser's same-prefix assumption therefore misses every chapter.
 */
internal object SushiScanChapters {
    private val hosts=setOf("sushiscan.net")
    internal fun isSeries(raw:String):Boolean=runCatching{
        val u=URI(raw)
        val h=u.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        h in hosts && u.scheme=="https" &&
            Regex("^/catalogue/[a-z0-9-]+/?$",RegexOption.IGNORE_CASE)
                .matches(u.path.orEmpty())
    }.getOrDefault(false)
    internal fun isReader(raw:String):Boolean=runCatching{
        val u=URI(raw)
        val h=u.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        h in hosts && u.scheme=="https" &&
            Regex("^/[a-z0-9-]+-(?:chapitre|volume)-[0-9]+(?:[._][0-9]+)?/?$",
                RegexOption.IGNORE_CASE).matches(u.path.orEmpty())
    }.getOrDefault(false)

    internal fun chapters(doc:Document,seriesUrl:String):List<ChapterLink>{
        require(isSeries(seriesUrl)){"Expected SushiScan series URL"}
        val u=URI(seriesUrl)
        val slug=u.path.trim('/').substringAfter("catalogue/")
        val pattern=Regex("^/"+Regex.escape(slug)+
            "-(chapitre|volume)-([0-9]+(?:[._][0-9]+)?)/?$",
            RegexOption.IGNORE_CASE)
        val found=LinkedHashMap<String,ChapterLink>()
        for(link in doc.select("a[href]").take(2000)){
            val raw=link.absUrl("href").substringBefore('#')
            val address=runCatching{URI(raw)}.getOrNull()?:continue
            if(address.scheme!="https" ||
                address.host.orEmpty().removePrefix("www.") !in hosts)continue
            val match=pattern.matchEntire(address.path.orEmpty())?:continue
            val type=match.groupValues[1].lowercase(Locale.ROOT)
            val number=match.groupValues[2].replace('_','.')
            val title=(if(type=="volume")"Volume " else "Chapter ")+number
            found.putIfAbsent(address.toString().trimEnd('/')+"/",
                ChapterLink(address.toString().trimEnd('/')+"/",title))
        }
        return ChapterScraper.sort(found.values.toList())
    }
}
