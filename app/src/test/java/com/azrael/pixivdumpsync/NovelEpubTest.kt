package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class NovelEpubTest {
    private val source="https://freewebnovel.com/novel/my-favorite-book"
    @Test fun onlyRecognizedNovelSeriesAreEnqueued(){
        assertTrue(NovelEpubParser.supportedSeries(source))
        assertTrue(NovelEpubParser.supportedSeries(
            "https://novelbuddy.com/novel/my-book"))
        assertFalse(NovelEpubParser.supportedSeries(source+"/chapter-1"))
        assertFalse(NovelEpubParser.supportedSeries("https://malicious.invalid/novel/book"))
        assertFalse(NovelEpubParser.supportedSeries("http://freewebnovel.com/novel/book"))
    }
    @Test fun chapterCollectorRetainsOrderAndRejectsSidebarCrossSeries(){
        val doc=Jsoup.parse("""<main class="chapter-list">
            <a href="/novel/my-favorite-book/chapter-20">Chapter 20</a>
            <a href="/novel/my-favorite-book/chapter-1">Chapter 1</a>
            <a href="/novel/other-book/chapter-3">Wrong novel</a>
            <a href="https://outsider.net/novel/my-favorite-book/chapter-9">Foreign</a>
            </main>""",source)
        val hits=NovelEpubParser.chapters(doc,source)
        assertEquals(listOf(1.0,20.0),hits.map{it.number})
        assertTrue(hits.none{it.url.contains("other-book")})
    }
    @Test fun chapterIndexDetectsHiddenPages(){
        val doc=Jsoup.parse("""<div class="chapter-list">Chapter List</div>
            <div class="pagination"><a>C.1 - C.40</a><a>C.41 - C.80</a></div>""",source)
        assertEquals(80,NovelEpubParser.chapterCountHint(doc))
    }
    @Test fun chapterReaderDoesNotIncludeAdsOrScript(){
        val doc=Jsoup.parse("""<h1>Chapter 1</h1>
          <div id="chapter-content"><script>alert(1)</script>
          <div class="ad">buy this</div>
          <p>First paragraph of content, sufficiently long to be meaningful and complete.</p>
          <p>Second paragraph with symbols &amp; words and a little more writing.</p>
          </div>""",source+"/chapter-1")
        val chapter=NovelEpubParser.chapterText(doc)
        assertEquals(2,chapter.paragraphs.size)
        assertFalse(chapter.paragraphs.joinToString().contains("buy this"))
    }
    @Test fun epubContainsMimeFirstAllSpineEntriesAndEscapedText(){
        val buffer=ByteArrayOutputStream()
        NovelEpubParser.writeEpub(buffer,"Title & More",source,listOf(
            NovelText("Chapter 1",listOf("First & <second> paragraph")),
            NovelText("Chapter 2",listOf("Final chapter text"))))
        val entries=LinkedHashMap<String,String>()
        ZipInputStream(ByteArrayInputStream(buffer.toByteArray())).use{zip->
            while(true){
                val next=zip.nextEntry?:break
                entries[next.name]=zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }
        assertEquals("mimetype",entries.keys.first())
        assertEquals("application/epub+zip",entries["mimetype"])
        assertTrue(entries.containsKey("META-INF/container.xml"))
        assertTrue(entries["OEBPS/content.opf"]!!.contains("chapter-2.xhtml"))
        assertTrue(entries["OEBPS/content.opf"]!!.contains("idref=\"ch2\""))
        assertTrue(entries["OEBPS/nav.xhtml"]!!.contains("Chapter 2"))
        assertTrue(entries["OEBPS/chapter-1.xhtml"]!!.contains("&amp; &lt;second&gt;"))
    }
}
