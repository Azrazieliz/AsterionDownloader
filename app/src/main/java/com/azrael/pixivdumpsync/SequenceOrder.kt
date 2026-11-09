package com.azrael.pixivdumpsync

import java.util.Locale

/**
 * Ordering belongs to the *content*, not the website.
 *
 * A comic's chapters and pages, or a novel's chapters and a series' volumes,
 * have an authored sequence. An ordinary photo album, gallery or loose file
 * collection has no implied sequence and is NEVER sorted by filename.
 */
object SequenceOrder {
    enum class Kind { CHAPTER, PAGE, EPISODE, VOLUME, UNORDERED }

    private val numbered=mapOf(
        Kind.CHAPTER to Regex(
            """(?i)(?:^|[/ _\-.])(?:chapters?|chapitres?|ch\.?)[\s_./:#-]*(\d+)(?:[._](\d+))?"""),
        Kind.PAGE to Regex(
            """(?i)(?:^|[/ _\-.])(?:pages?|pg|p)[\s_./:#-]*(\d+)(?:[._](\d+))?"""),
        Kind.EPISODE to Regex(
            """(?i)(?:^|[/ _\-.])(?:episodes?|eps?|e)[\s_./:#-]*(\d+)(?:[._](\d+))?"""),
        Kind.VOLUME to Regex(
            """(?i)(?:^|[/ _\-.])(?:volumes?|vol\.?|v)[\s_./:#-]*(\d+)(?:[._](\d+))?""")
    )
    private val seasonEpisode=Regex("""(?i)\bs(\d{1,3})[\s._-]*e(\d{1,4})\b""")
    data class Index(val major:Long,val minor:Long=0) : Comparable<Index> {
        override fun compareTo(other:Index):Int {
            val primary=major.compareTo(other.major)
            return if(primary!=0)primary else minor.compareTo(other.minor)
        }
    }
    fun index(kind:Kind,raw:String):Index? {
        if(kind==Kind.UNORDERED)return null
        if(kind==Kind.EPISODE){
            seasonEpisode.find(raw)?.let{
                val season=it.groupValues[1].toLongOrNull()?:return null
                val episode=it.groupValues[2].toLongOrNull()?:return null
                return Index(season,episode)
            }
        }
        val match=numbered[kind]?.find(raw)?:return null
        val major=match.groupValues[1].toLongOrNull()?:return null
        val minor=match.groupValues.getOrElse(2){""}
            .takeIf(String::isNotBlank)?.toLongOrNull()?:0L
        return Index(major,minor)
    }

    /** Stable numerical ordering only for an explicitly ordered content type.
     * Unknown indices are never guessed and keep their original relative order.
     */
    fun <T> arrange(items:List<T>,kind:Kind,title:(T)->String,
                    fallback:(T)->String={""}):List<T> {
        if(kind==Kind.UNORDERED)return items
        return items.withIndex().sortedWith(
            compareBy<IndexedValue<T>>{
                val n=index(kind,title(it.value))?:index(kind,fallback(it.value))
                if(n==null)1 else 0
            }.thenBy{
                index(kind,title(it.value))?:index(kind,fallback(it.value))
            }.thenBy{it.index}
        ).map{it.value}
    }

    /** Reader HTML can list numbered pages in reverse DOM order.
     * Reorder ONLY when every page has an explicit, unique page index;
     * otherwise retain the original document order. Not for photo albums.
     */
    fun comicPages(urls:List<String>):List<String> {
        val indices=urls.map{url->
            val path=runCatching{java.net.URI(url).path.orEmpty()}
                .getOrDefault(url)
            index(Kind.PAGE,path)?:run {
                // Some comic readers use /pages/001.jpg with no "page" word.
                val parent=Regex("""(?i)/(?:pages?|chapters?)/\d{1,9}(?:/|$)""")
                    .containsMatchIn(path)
                val match=if(parent)
                    Regex("""/(\d{1,6})\.(?:jpe?g|png|gif|webp|avif)$""",
                        RegexOption.IGNORE_CASE).find(path)
                else null
                match?.groupValues?.get(1)?.toLongOrNull()?.let{Index(it)}
            }
        }
        if(indices.any{it==null} || indices.filterNotNull().toSet().size!=urls.size)
            return urls
        return urls.indices.sortedWith(
            compareBy<Int>{indices[it]}.thenBy{it}
        ).map{urls[it]}
    }

    /** For volume acquisition, a missing lower volume blocks higher volumes.
     * An unknown volume does not make up a number or change a photo's order.
     */
    fun nextVolume(firstVolume:Int,completed:Set<Int>,
                   available:Set<Int>):Int?{
        val candidate=firstVolume.coerceAtLeast(1)
        var next=candidate
        while(next in completed)next++
        return next.takeIf{it in available}
    }
}
