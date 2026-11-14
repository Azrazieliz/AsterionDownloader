package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class UnifiedDownloadTest {
    @Test fun routesXPosts() {
        assertEquals(DownloadKind.X,SourceRouter.classify("https://x.com/creator/status/1234567"))
        assertTrue(SourceRouter.xPost("https://twitter.com/creator/status/1234567"))
    }
    @Test fun routesOriginalPixivAndArtist() {
        assertEquals(DownloadKind.PIXIV,SourceRouter.classify("https://www.pixiv.net/en/users/123456"))
        assertEquals("123456",SourceRouter.pixivArtistId("https://www.pixiv.net/en/users/123456"))
    }
    @Test fun routesComicsAndVideos() {
        assertEquals(DownloadKind.COMIC,SourceRouter.classify("https://example.com/manga/story/chapter-12"))
        assertEquals(DownloadKind.VIDEO,SourceRouter.classify("https://example.com/watch?v=2"))
    }
    @Test fun chaptersAreNaturallyOrderedAndDeduplicated() {
        val a=ChapterLink("https://example.com/9","Chapter 9")
        val b=ChapterLink("https://example.com/10","Chapter 10")
        val c=ChapterLink("https://example.com/2","Chapter 2")
        assertEquals(listOf(c,a,b),ChapterScraper.sort(listOf(b,a,c,c)))
        assertEquals(75.5,ChapterScraper.naturalKey("Chapter 75.5 - Season 1"),0.001)
    }
}
