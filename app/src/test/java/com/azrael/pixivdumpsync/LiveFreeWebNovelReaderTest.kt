package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** Opt-in real publisher test: Android's exact Jsoup EPUB parser and writer.
 * This downloads only two public chapters, never claims to archive all 1147.
 * Set ASTERION_LIVE_NOVELS=1 when invoking this individual test.
 */
class LiveFreeWebNovelReaderTest {
    @Test fun observedReaderAndTwoRealChaptersWriteValidOfflineEpub(){
        Assume.assumeTrue("Not an offline fixture",
            System.getenv("ASTERION_LIVE_NOVELS")=="1")
        val root="https://freewebnovel.com/novel/dual-cultivation-novel"
        // The real JVM Jsoup transport receives HTTP 403 on this
        // publicly browser-accessible publisher. The existing live
        // integration helper fetches actual bytes through a browser-
        // compatible HTTP client when that occurs (Android production
        // uses BrowserPageFetch, not Python).
        val transport=ActualProviderIntegrationTest()
        fun get(path:String)=transport.page(path)
        val index=get(root)
        val chapters=NovelEpubParser.chapters(index,root)
        assertTrue("Actual publisher listing has no discovered chapter",chapters.size>=30)
        val first=chapters.minByOrNull{it.number}!!
        assertEquals(1.0,first.number,0.001)
        val firstPage=get(first.url)
        val firstNumber=NovelEpubParser.readerChapter(firstPage,first.url,first)
        assertEquals(1.0,firstNumber.number,0.001)
        val secondUrl=NovelEpubParser.nextReader(firstPage,root,first.url)
        assertNotNull("Reader did not publish next chapter",secondUrl)
        assertTrue(secondUrl!!.endsWith("/chapter-2"))
        val secondPage=get(secondUrl)
        assertEquals(2.0,
            NovelEpubParser.readerChapter(secondPage,secondUrl).number,0.001)
        val two=listOf(NovelEpubParser.chapterText(firstPage),
            NovelEpubParser.chapterText(secondPage))
        assertTrue(two.all{it.paragraphs.sumOf(String::length)>80})
        val finalUrl="$root/chapter-1147"
        assertNull("Observed final chapter unexpectedly links into another book",
            NovelEpubParser.nextReader(get(finalUrl),root,finalUrl))
        val file=File.createTempFile("asterion-real-novel-reader-", ".epub")
        try {
            file.outputStream().use{
                NovelEpubParser.writeEpubStreaming(it,
                    "Dual Cultivation",root,
                    listOf("Chapter 1","Chapter 2")){i->two[i]}
            }
            assertTrue(file.length()>2_000)
            ZipFile(file).use{archive->
                val mimetype=archive.getEntry("mimetype")
                assertNotNull(mimetype)
                assertEquals(0,mimetype.method)
                assertNotNull(archive.getEntry("OEBPS/chapter-1.xhtml"))
                assertNotNull(archive.getEntry("OEBPS/chapter-2.xhtml"))
                assertNotNull(archive.getEntry("OEBPS/content.opf"))
                assertNotNull(archive.getEntry("OEBPS/nav.xhtml"))
            }
            println("REAL_FREEWEBNOVEL_2_CHAPTER_EPUB_VERIFIED bytes="+file.length()+
                " chapter_index_count="+chapters.size+
                " next_reader_correct=true final_next_absent=true")
        }finally{file.delete()}
    }
}
