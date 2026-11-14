package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class SushiScanChaptersTest {
    @Test fun parsesRootLevelSushiScanChaptersAndVolumes(){
        val url="https://sushiscan.net/catalogue/vinland-saga/"
        val doc=Jsoup.parse("""<html><main>
            <ul><li><a href="/vinland-saga-chapitre-220/">Chapitre 220</a></li>
            <li><a href="/vinland-saga-chapitre-176.5/">Chapitre 176.5</a></li>
            <li><a href="/vinland-saga-volume-24/">Volume 24</a></li></ul>
            <a href="/catalogue/another-series/">Related series</a>
            <a href="/one-piece-chapitre-1180/">Unrelated chapter</a>
            <a href="https://evil.example/vinland-saga-chapitre-6/">External</a>
        </main></html>""",url)
        val links=SushiScanChapters.chapters(doc,url)
        assertEquals(3,links.size)
        assertTrue(links.all{it.url.startsWith("https://sushiscan.net/")})
        assertTrue(links[0].url.endsWith("vinland-saga-volume-24/"))
        assertTrue(links[1].url.endsWith("vinland-saga-chapitre-176.5/"))
        assertTrue(links[2].url.endsWith("vinland-saga-chapitre-220/"))
        assertTrue(SourceIndexParser.autoOriginal(IndexedItem(
            url,"Vinland Saga",null,url,"COMICS","comics",1L,false)))
    }
    @Test fun onlyExactSeriesPagesAndReaderUrlsAreSupported(){
        assertTrue(SushiScanChapters.isSeries(
            "https://sushiscan.net/catalogue/vinland-saga/"))
        assertTrue(SushiScanChapters.isReader(
            "https://sushiscan.net/vinland-saga-chapitre-220/"))
        assertFalse(SushiScanChapters.isSeries("https://sushiscan.net/catalogue/"))
        assertFalse(SushiScanChapters.isSeries("https://evil.sushiscan.net.evil.com/catalogue/x/"))
        assertFalse(SushiScanChapters.isReader("https://sushiscan.net/other/path"))
    }
}