package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class ChapterScraperTest {
    @Test fun sortsFractionalChaptersAndPreservesOriginalOrdering(){
        val original=listOf(
            ChapterLink("https://comics.example/serie/book/chapter-10/","Chapter 10"),
            ChapterLink("https://comics.example/serie/book/chapter-2_5/","Chapter 2.5"),
            ChapterLink("https://comics.example/serie/book/chapter-2/","Chapter 2"),
            ChapterLink("https://comics.example/serie/book/chapter-1/","Chapter 1"))
        val sorted=ChapterScraper.sort(original)
        assertEquals(listOf("Chapter 1","Chapter 2","Chapter 2.5","Chapter 10"),
            sorted.map{it.title})
    }
    @Test fun detectsWholeSeriesVsSingleChapter(){
        assertFalse(ChapterScraper.isSingleChapter(
            "https://toonily.com/serie/moby-dick-17bba228/"))
        assertTrue(ChapterScraper.isSingleChapter(
            "https://toonily.com/serie/moby-dick-17bba228/chapter-12_1/"))
    }
    @Test fun selectsOriginalLazyImagesAndExcludesLogos(){
        val html="""<div class="reading-content">
          <img src="/loading.gif" data-src="/chapter/page-01.webp" />
          <img src="/chapter/page-02.webp" />
          <img src="/images/logo.png" />
        </div>"""
        val doc=Jsoup.parse(html,"https://reader.example/serie/test/chapter-1/")
        assertEquals(listOf("https://reader.example/chapter/page-01.webp",
            "https://reader.example/chapter/page-02.webp"),
            ChapterScraper.parsePageImages(doc))
    }
    @Test fun mangaReadReaderAreaPreservesPagesInOrder(){
        val html="""<main><div id="readerarea">
          <img data-original="https://cdn.mangaread.org/pages/01.webp" src="/loading.gif"/>
          <img data-cfsrc="/pages/02.png" src="/loading.gif"/>
          <img src="/pages/03.jpg"/>
        </div></main>"""
        val doc=Jsoup.parse(html,"https://www.mangaread.org/manga/reader/chapter-1/")
        assertEquals(listOf("https://cdn.mangaread.org/pages/01.webp",
            "https://www.mangaread.org/pages/02.png",
            "https://www.mangaread.org/pages/03.jpg"),
            ChapterScraper.parsePageImages(doc))
        assertTrue(ChapterScraper.isSingleChapter(
            "https://www.mangaread.org/manga/reader/chapter-1/"))
    }
    @Test fun browserSnapshotKeepsOriginalReaderImageOrder(){
        val html="""<html><body><a href="/serie/book/chapter-3/">Chapter 3</a>
            <div id="reader">
              <img class="page" src="https://cdn.example.com/chapters/p1.jpg"/>
              <img class="page" data-src="https://cdn.example.com/chapters/p2.webp" src="loading.gif"/>
            </div></body></html>"""
        val doc=Jsoup.parse(html,"https://toonily.com/serie/book/chapter-3/")
        assertEquals(listOf("https://cdn.example.com/chapters/p1.jpg",
            "https://cdn.example.com/chapters/p2.webp"),
            ChapterScraper.parsePageImages(doc))
    }
    @Test fun mangaReadSeriesGetsComicsRouting(){
        assertEquals(DownloadKind.COMIC,SourceRouter.classify(
            "https://www.mangaread.org/manga/reader/"))
        assertEquals(DownloadKind.COMIC,SourceRouter.classify(
            "https://toonily.com/serie/beginning-after-end/"))
    }
    @Test fun doesNotConfuseSeriesSlugWithChapterNumber(){
        assertEquals(12.5,ChapterScraper.naturalKey("Chapter 12.5"),0.001)
        assertEquals(Double.MAX_VALUE,ChapterScraper.naturalKey("Moby Dick 17bba228"),0.001)
    }
}
