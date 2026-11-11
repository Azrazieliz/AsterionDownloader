package com.azrael.pixivdumpsync

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class NovelChapter(val number:Double,val title:String,val url:String)
data class NovelText(val title:String,val paragraphs:List<String>)

/** Extract only actual numbered chapters from the same novel, never sidebar links. */
object NovelEpubParser {
    private val number=Regex("""(?i)(?:^|[/\s_-])(?:chapter|chapitre|ch|episode)[/_\s.-]*(\d+(?:[._]\d+)?)""")
    private val readerHosts=setOf(
        "freewebnovel.com","novelbuddy.com","novelbuddy.me","readnovelfull.com")
    private fun canonicalReaderHost(host:String?):String{
        val normalized=host.orEmpty().lowercase().removePrefix("www.")
        return if(normalized=="novelbuddy.com")"novelbuddy.me" else normalized
    }
    private fun sameReaderHost(a:String?,b:String?):Boolean{
        val host=canonicalReaderHost(a)
        return host.isNotBlank() && host==canonicalReaderHost(b)
    }
    /** Text-based novels are always EPUB. ZIP is for image-based series. */
    fun readableZipSeries(url:String):Boolean=false
    fun supportedSeries(url:String):Boolean = runCatching{
        val uri=URI(url)
        val host=uri.host.lowercase().removePrefix("www.")
        val path=uri.path.trimEnd('/')
        uri.scheme=="https" && host in readerHosts &&
            (path.contains("/novel/") || path.contains("/book/")) &&
            !Regex("""(?i)/(?:chapter|chapitre)[-_]?\d+""").containsMatchIn(path) &&
            uri.userInfo==null
    }.getOrDefault(false)
    /** Never silently package only the visible page of a paginated chapter list. */
    fun chapterCountHint(doc:Document):Int {
        val chapterRegion=doc.selectFirst(
            "#chapter-list,.chapter-list,.chapter-list-container,"+
            ".novel-chapters,#chapters,.chapters-list")
        val text=(chapterRegion?.parent()?.text().orEmpty()+" "+
            doc.select(".pagination,.chapter-pagination,.chapter-page-list")
                .text()).take(50000)
        val ranges=Regex("""(?i)C\s*\.\s*\d+\s*[-–—]\s*C\s*\.\s*(\d+)""")
            .findAll(doc.text()).mapNotNull{it.groupValues[1].toIntOrNull()}
        val total=Regex("""(?i)(\d{1,6})\s+(?:total\s+)?chapters\b""")
            .findAll(text).mapNotNull{it.groupValues[1].toIntOrNull()}
        return (ranges+total).maxOrNull()?:0
    }
    internal fun parseChapterNumber(input:String):Double? =
        number.find(input)?.groupValues?.get(1)?.replace('_','.')?.toDoubleOrNull()
    fun chapters(doc:Document,source:String):List<NovelChapter>{
        val root=URI(source)
        val home=root.path.trimEnd('/')
        val found=LinkedHashMap<String,NovelChapter>()
        for(a in doc.select("a[href]").take(10000)){
            val url=runCatching{root.resolve(a.attr("href"))}.getOrNull()?:continue
            if(url.scheme!="https" || !sameReaderHost(url.host,root.host) ||
                url.userInfo!=null || url.fragment!=null ||
                !url.path.startsWith(home+"/"))continue
            // The displayed chapter number may differ from the provider's URL ID.
            // FreeWebNovel uses chapter-1596 for a displayed Chapter 1314.
            val n=parseChapterNumber(a.text())?:parseChapterNumber(url.path)?:continue
            if(n<0.0 || n>100000.0 || !n.isFinite())continue
            val title=a.text().trim().ifBlank{"Chapter "+n}
            found.putIfAbsent(url.toString(),NovelChapter(n,title.take(180),url.toString()))
        }
        return found.values.sortedWith(compareBy<NovelChapter>{it.number}.thenBy{it.url})
    }
    fun nextIndex(doc:Document,source:String,visited:Set<String>):String?{
        val root=URI(source)
        fun resolveLink(href:String):URI {
            // java.net.URI.resolve("?page=2") drops the final path segment,
            // incorrectly turning /novel/book into /novel/?page=2.
            val base=URI(doc.location())
            return if(href.startsWith("?"))URI(base.toString().substringBefore('?')+href)
                else base.resolve(href)
        }
        val candidates=doc.select("a[rel=next][href],.chapter-list a.next[href],"+
            ".pagination a.next[href],a.page-numbers.next[href],"+
            ".chapter-pagination a.next[href]")
        for(a in candidates) {
            val url=runCatching{resolveLink(a.attr("href"))}.getOrNull()
                ?:continue
            if(url.scheme=="https" && sameReaderHost(url.host,root.host) &&
                url.path.trimEnd('/')==root.path.trimEnd('/') &&
                url.toString() !in visited)return url.toString()
        }
        // Some providers expose page links without a rel=next class. Follow
        // only explicit same-series pagination URLs; never synthesize endpoints.
        val currentPage=Regex("""(?:^|[?&])page=(\d+)""")
            .find(URI(doc.location()).rawQuery?.let{"?"+it}.orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()?:1
        val links=doc.select("a[href]").mapNotNull{a->
            val next=runCatching{resolveLink(a.attr("href"))}
                .getOrNull()?:return@mapNotNull null
            if(next.scheme!="https" || !sameReaderHost(next.host,root.host) ||
                next.path.trimEnd('/')!=root.path.trimEnd('/') ||
                next.toString() in visited)return@mapNotNull null
            val page=Regex("""(?:^|[?&])page=(\d+)""")
                .find(next.rawQuery?.let{"?"+it}.orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull()?:return@mapNotNull null
            if(page>currentPage)page to next.toString() else null
        }
        return links.minByOrNull{it.first}?.second
    }
    /** Follow a provider's actual Next Chapter href rather than guessing IDs. */
    fun nextReader(doc:Document,source:String,current:String):String? {
        val home=URI(source)
        for(a in doc.select("a[href]")){
            val label=a.text().trim()
            val next=a.id()=="next_url" ||
                label.matches(Regex("""(?i)^next\s*(chapter)?\s*[→»›]?$""")) ||
                a.attr("rel").equals("next",true) ||
                a.classNames().any{it.contains("next",true)}
            if(!next)continue
            val dest=runCatching{URI(doc.location()).resolve(a.attr("href"))}
                .getOrNull()?:continue
            if(dest.scheme=="https" && sameReaderHost(dest.host,home.host) &&
                dest.userInfo==null && dest.fragment==null &&
                dest.path.startsWith(home.path.trimEnd('/')+"/") &&
                parseChapterNumber(dest.path)!=null &&
                dest.toString()!=current)return dest.toString()
        }
        return null
    }
    /** The visible title is authoritative: chapter-N URLs can use different IDs. */
    fun readerChapter(doc:Document,address:String):NovelChapter {
        val heading=doc.select("h1,h2,h3,h4,.chapter-title")
            .map{it.text().trim()}
            .firstOrNull{parseChapterNumber(it)!=null}
            ?:throw IllegalStateException("Reader chapter number not visible")
        val number=parseChapterNumber(heading)
            ?:throw IllegalStateException("Reader chapter number missing")
        return NovelChapter(number,heading.take(180),address)
    }
    fun chapterText(doc:Document):NovelText {
        // FreeWebNovel's live reader wraps its text in #article, with the
        // numbered heading inside h4. Generic readers use the other selectors.
        val content=doc.selectFirst("#article,#chapter-content,.chapter-content,"+
            "#chapterContent,.reading-content,.read-content,.novel-content,"+
            "#chapter-body,#chapter-body-content,.txtnav,.text-left")
            ?:throw IllegalStateException("No novel chapter reader was found")
        val cloned=content.clone()
        cloned.select("script,style,iframe,form,aside,nav,.ads,.ad,.advertisement,"+
            ".chapter-nav,.chapter-controls,.reading-settings").remove()
        val paragraphs=cloned.select("p").map {it.text().trim()}
            .filter{it.isNotBlank() && it.length<20000}
            .ifEmpty{
                cloned.wholeText().replace("\r","")
                    .split(Regex("\n{2,}")).map{it.trim()}
                    .filter{it.isNotBlank() && it.length<20000}
            }
        require(paragraphs.sumOf{it.length}>=80) {
            "Chapter content missing or truncated; refusing an incomplete EPUB"
        }
        val title=doc.selectFirst("h1,h2,h3")?.text()?.trim()
            ?.takeIf{it.isNotBlank()}?:doc.title()
        return NovelText(title.take(180),paragraphs)
    }
    private fun quote(raw:String):String=raw.replace("&","&amp;")
        .replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
        .replace("'","&apos;")

    /**
     * Single portable reader archive: an offline HTML table of contents,
     * individually ordered chapter pages, and a standard EPUB for readers.
     * Call only after the entire chapter list and every text are complete.
     */
    fun writeReadableZip(output:OutputStream,bookTitle:String,source:String,
                         chapters:List<NovelText>){
        require(chapters.isNotEmpty() && chapters.size<=10000)
        ZipOutputStream(output.buffered()).use{zip->
            fun add(name:String,bytes:ByteArray){
                val entry=ZipEntry(name)
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
            fun addText(name:String,content:String)=add(
                name,content.toByteArray(StandardCharsets.UTF_8))
            val toc=StringBuilder()
            for((i,chapter) in chapters.withIndex()){
                val path="chapters/chapter-"+(i+1).toString().padStart(5,'0')+".html"
                toc.append("<li><a href=\"").append(path).append("\">")
                    .append(quote(chapter.title)).append("</a></li>")
            }
            addText("index.html", """<!doctype html><html lang="en"><head>
                <meta charset="utf-8"><meta name="viewport" content="width=device-width">
                <title>"""+quote(bookTitle)+"""</title></head><body>
                <h1>"""+quote(bookTitle)+"""</h1><p>"""+chapters.size+
                """ chapters. Offline readable archive. Source: """+
                "<a href=\""+quote(source)+"\">Original series</a>"+
                ".</p><ol>"+toc+
                "</ol></body></html>")
            for((i,chapter) in chapters.withIndex()){
                val id=(i+1).toString().padStart(5,'0')
                val path="chapters/chapter-"+id+".html"
                val next=if(i+1<chapters.size)
                    "<a href=\"chapter-"+(i+2).toString().padStart(5,'0')+
                    ".html\">Next chapter</a>" else ""
                val previous=if(i>0)
                    "<a href=\"chapter-"+i.toString().padStart(5,'0')+
                    ".html\">Previous chapter</a> " else ""
                val body=chapter.paragraphs.joinToString(""){"<p>"+quote(it)+"</p>"}
                addText(path,"""<!doctype html><html lang="en"><head>
                    <meta charset="utf-8"><meta name="viewport" content="width=device-width">
                    <title>"""+quote(chapter.title)+"""</title></head><body>
                    <nav><a href="../index.html">Contents</a></nav><h1>"""+
                    quote(chapter.title)+"</h1>"+body+"<nav>"+previous+next+
                    "</nav></body></html>")
            }
            val epub=ByteArrayOutputStream()
            writeEpub(epub,bookTitle,source,chapters)
            add("book.epub",epub.toByteArray())
        }
    }

    /** Standard EPUB3: STORED mimetype first, XHTML chapters, nav and OPF/spine.
     * Caller commits this output to MediaStore only after all chapters succeeded.
     */
    fun writeEpub(output:OutputStream,bookTitle:String,source:String,
                  chapters:List<NovelText>) =
        writeEpubStreaming(output,bookTitle,source,
            chapters.map{it.title}){chapters[it]}

    /**
     * Stream each chapter separately into the EPUB. A multi-thousand-chapter
     * book must never hold every chapter's text in Android's Java heap.
     */
    fun writeEpubStreaming(output:OutputStream,bookTitle:String,source:String,
                           chapterTitles:List<String>,
                           readChapter:(Int)->NovelText){
        require(chapterTitles.isNotEmpty() && chapterTitles.size<=10000)
        val title=quote(bookTitle)
        val identifier=UUID.nameUUIDFromBytes(source.toByteArray(StandardCharsets.UTF_8))
        ZipOutputStream(output.buffered()).use{zip->
            fun bytes(name:String,value:String,stored:Boolean=false){
                val data=value.toByteArray(StandardCharsets.UTF_8)
                val entry=ZipEntry(name)
                if(stored) {
                    entry.method=ZipEntry.STORED
                    entry.size=data.size.toLong()
                    val crc=CRC32();crc.update(data);entry.crc=crc.value
                }
                zip.putNextEntry(entry);zip.write(data);zip.closeEntry()
            }
            bytes("mimetype","application/epub+zip",true)
            bytes("META-INF/container.xml",
                """<?xml version="1.0" encoding="UTF-8"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            val manifest=StringBuilder()
            val spine=StringBuilder()
            val nav=StringBuilder()
            for((i,heading) in chapterTitles.withIndex()){
                val chapter=readChapter(i)
                require(chapter.paragraphs.isNotEmpty()){
                    "EPUB chapter text missing for "+heading
                }
                val n=i+1
                val id="ch"+n
                val path="chapter-"+n+".xhtml"
                manifest.append("<item id=\"").append(id).append("\" href=\"")
                    .append(path).append("\" media-type=\"application/xhtml+xml\"/>")
                spine.append("<itemref idref=\"").append(id).append("\"/>")
                nav.append("<li><a href=\"").append(path).append("\">")
                    .append(quote(chapter.title)).append("</a></li>")
                val body=chapter.paragraphs.joinToString(""){"<p>"+quote(it)+"</p>"}
                bytes("OEBPS/"+path,
                    """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>"""+
                    quote(chapter.title)+"""</title><meta charset="utf-8"/></head><body><h2>"""+
                    quote(chapter.title)+"</h2>"+body+"</body></html>")
            }
            bytes("OEBPS/nav.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><h1>Contents</h1><ol>"""+
                nav.toString()+"</ol></nav></body></html>")
            bytes("OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" unique-identifier="bookid" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="bookid">urn:uuid:"""+
                identifier+"""</dc:identifier><dc:title>"""+title+
                """</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>"""+
                manifest.toString()+"</manifest><spine>"+spine+"</spine></package>")
        }
    }
}
