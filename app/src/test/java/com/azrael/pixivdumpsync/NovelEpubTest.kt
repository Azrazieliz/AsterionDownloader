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
    @Test fun suppliedFreewebnovelTitleSelectsCompleteReadableZip(){
        val target="https://freewebnovel.com/novel/i-am-the-fated-villain"
        assertTrue(NovelEpubParser.supportedSeries(target))
        assertFalse(NovelEpubParser.readableZipSeries(target))
        assertFalse(NovelEpubParser.readableZipSeries(target+"/chapter-1"))
        assertFalse(NovelEpubParser.readableZipSeries(
            "https://novelbuddy.com/novel/i-am-the-fated-villain"))
        assertFalse(NovelEpubParser.readableZipSeries(
            "https://readnovelfull.com/book/my-favorite-novel"))
        assertFalse(NovelEpubParser.readableZipSeries(
            "https://mp4directs.com/threads/example.123"))
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
    @Test fun freewebnovelUsesChapterDisplayNumbersNotOpaqueUrlIds(){
        val doc=Jsoup.parse("""<main class="chapter-list">
            <a href="/novel/my-favorite-book/chapter-1596">Chapter 1314: The Agreement</a>
            <a href="/novel/my-favorite-book/chapter-1">Chapter 1: Beginning</a>
            </main>""",source)
        assertEquals(listOf(1.0,1314.0),
            NovelEpubParser.chapters(doc,source).map{it.number})
    }
    @Test fun explicitPageLinksAreFollowedWithoutInventingUnverifiedEndpoints(){
        val doc=Jsoup.parse("""<div class="chapter-list">
            <a href="?page=4">4</a><a href="?page=2">2</a>
            <a href="/novel/other-book?page=3">Unrelated</a>
            </div>""",source)
        assertEquals(source+"?page=2",
            NovelEpubParser.nextIndex(doc,source,setOf(source)))
    }
    @Test fun freewebnovelActualReaderArticleAndNextIdAreParsed() {
        val current="https://freewebnovel.com/novel/i-am-the-fated-villain/chapter-1"
        val doc=Jsoup.parse("""<h1>I Am the Fated Villain</h1>
            <div id="article"><h4>Chapter 1: Young Lord, Gu Changge</h4>
              <p>First authentic chapter paragraph: this is a sufficiently long sentence to validate the chapter reader and provide meaningful content.</p>
              <p>Second paragraph: chapter extraction must keep its text in the correct order without including the surrounding site menus.</p>
            </div>
            <nav><a id="next_url" href="/novel/i-am-the-fated-villain/chapter-2">›</a></nav>
            """,current)
        val text=NovelEpubParser.chapterText(doc)
        assertEquals(2,text.paragraphs.size)
        assertEquals(1.0,NovelEpubParser.readerChapter(doc,current).number,0.0)
        assertEquals("https://freewebnovel.com/novel/i-am-the-fated-villain/chapter-2",
            NovelEpubParser.nextReader(doc,
                "https://freewebnovel.com/novel/i-am-the-fated-villain",current))
    }
    @Test fun dualCultivationReaderUsesRealBrowserTitleWhenHeadingOnlyShowsNovel(){
        val sourceUrl="https://freewebnovel.com/novel/dual-cultivation-novel"
        val chapterUrl=sourceUrl+"/chapter-1"
        val html="""<html><head>
           <title>Dual Cultivation - Chapter 1 Su Yang | Free Web Novel</title>
           <meta property="og:title" content="Chapter 1 Su Yang"/>
           </head><body><h1>Dual Cultivation</h1>
           <div id="article"><p>This is an authentic chapter paragraph with a
             sufficiently long text body, the current source may render chapter
             numbers in document title rather than a heading element.</p></div>
           <a id="next_url" href="/novel/dual-cultivation-novel/chapter-2">
             Next Chapter</a></body></html>"""
        val document=Jsoup.parse(html,chapterUrl)
        val chapter=NovelEpubParser.readerChapter(document,chapterUrl)
        assertEquals(1.0,chapter.number,0.0)
        assertTrue(chapter.title.contains("Chapter 1"))
        assertEquals(1,NovelEpubParser.chapterText(document).paragraphs.size)
        assertEquals(sourceUrl+"/chapter-2",
            NovelEpubParser.nextReader(document,sourceUrl,chapterUrl))
    }
    @Test fun freeWebNovelUsesDisplayedNumberNotOpaqueUrlIdInMetadata(){
        val url="https://freewebnovel.com/novel/example/chapter-1596"
        val page=Jsoup.parse("""<html><head><title>Book - Chapter 1314: Agreement | Free Web Novel</title>
          </head><body><h1>Book</h1></body></html>""",url)
        assertEquals(1314.0,NovelEpubParser.readerChapter(page,url).number,0.0)
    }
    @Test fun readerFollowsTrueNextLinkAndUsesChapterHeading(){
        val doc=Jsoup.parse("""<h1>I Am the Fated Villain</h1>
            <h4>Chapter 1314: My Chapter</h4>
            <a href="/novel/my-favorite-book/chapter-1597">Next Chapter</a>
            <a href="/novel/my-favorite-book/chapter-1595">Prev Chapter</a>
            <a href="/novel/other-book/chapter-1598">Next Chapter</a>
            """,source+"/chapter-1596")
        assertEquals(1314.0,NovelEpubParser.readerChapter(doc,
            source+"/chapter-1596").number,0.0)
        assertEquals(source+"/chapter-1597",NovelEpubParser.nextReader(
            doc,source,source+"/chapter-1596"))
    }
    @Test fun readableZipHasOfflineIndexOrderedHtmlAndEpub(){
        val buffer=ByteArrayOutputStream()
        NovelEpubParser.writeReadableZip(buffer,"Title & More",source,listOf(
            NovelText("Chapter 1",listOf("First & <second> paragraph")),
            NovelText("Chapter 2",listOf("Final chapter text"))))
        val entries=LinkedHashMap<String,ByteArray>()
        ZipInputStream(ByteArrayInputStream(buffer.toByteArray())).use{zip->
            while(true){
                val entry=zip.nextEntry?:break
                entries[entry.name]=zip.readBytes()
                zip.closeEntry()
            }
        }
        assertEquals(listOf("index.html","chapters/chapter-00001.html",
            "chapters/chapter-00002.html","book.epub"),entries.keys.toList())
        assertTrue(entries["index.html"]!!.toString(Charsets.UTF_8)
            .contains("chapters/chapter-00002.html"))
        assertTrue(entries["chapters/chapter-00001.html"]!!
            .toString(Charsets.UTF_8).contains("&amp; &lt;second&gt;"))
        ZipInputStream(ByteArrayInputStream(entries["book.epub"]!!)).use{
            assertEquals("mimetype",it.nextEntry.name)
        }
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
    @Test fun streamedEpubWritesEveryChapterInOrderWithoutAWholeBookList(){
        val titles=listOf("Chapter 1","Chapter 2","Chapter 3")
        val accessed=ArrayList<Int>()
        val output=ByteArrayOutputStream()
        NovelEpubParser.writeEpubStreaming(output,"Streaming book",source,titles){i->
            accessed.add(i)
            NovelText(titles[i],listOf("Text body for chapter "+(i+1)))
        }
        assertEquals(listOf(0,1,2),accessed)
        val names=ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use{zip->
            while(true){
                val entry=zip.nextEntry?:break
                names.add(entry.name)
                zip.closeEntry()
            }
        }
        assertEquals("mimetype",names.first())
        assertTrue(names.contains("OEBPS/chapter-1.xhtml"))
        assertTrue(names.contains("OEBPS/chapter-2.xhtml"))
        assertTrue(names.contains("OEBPS/chapter-3.xhtml"))
        assertEquals("OEBPS/content.opf",names.last())
    }

    @Test fun chapterReaderChoosesRealBodyOverShortMatchingNotice(){
        val doc=Jsoup.parse("""<h1>Chapter 15</h1>
            <div id="article"><p>Ad notice</p></div>
            <div id="chapter-content"><p>The actual chapter begins here and
            continues with enough detailed narrative text to establish the real
            article body instead of rejecting the page because a small notice
            happened to use the #article selector.</p>
            <p>This additional paragraph belongs to the same chapter and is
            retained in the final offline book.</p></div>""",source+"/chapter-15")
        val chapter=NovelEpubParser.chapterText(doc)
        assertEquals(2,chapter.paragraphs.size)
        assertFalse(chapter.paragraphs.joinToString().contains("Ad notice"))
    }

}
