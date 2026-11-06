package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class IndexedMediaQualityTest {
    @Test fun rejectsLanguageDirectory(){
        assertFalse(IndexedMediaQuality.isMedia(
            "https://example.org/languages/portugues",
            "Português","https://example.org/flags/pt.png","ADULT"))
        assertFalse(IndexedMediaQuality.isMedia(
            "https://example.org/language/bahasa-indonesia",
            "Bahasa Indonesia","https://example.org/flags/id.png","ADULT"))
        assertFalse(IndexedMediaQuality.isMedia(
            "https://example.org/category/games","Best games",
            "https://example.org/preview.jpg","GAMES"))
    }
    @Test fun rejectsHomepageAndMenu(){
        assertFalse(IndexedMediaQuality.isMedia(
            "https://example.org/","Watch all videos","https://example.org/x.jpg","ADULT"))
        assertFalse(IndexedMediaQuality.isMedia(
            "https://example.org/search?q=test","Search",
            "https://example.org/p.jpg","ADULT"))
    }
    @Test fun acceptsRealPostsWithThumbnail(){
        assertTrue(IndexedMediaQuality.isMedia(
            "https://example.org/video/1234","Example episode",
            "https://example.org/thumbnail.jpg","HENTAI"))
        assertTrue(IndexedMediaQuality.isMedia(
            "https://example.org/post/abc","Artist illustration",
            "https://example.org/thumbnail.jpg","ARTWORK"))
    }
    @Test fun acceptsDirectFileWithoutThumbnail(){
        assertTrue(IndexedMediaQuality.isMedia(
            "https://example.org/downloads/volume1.epub","Volume 1",null,"BOOKS"))
        assertTrue(IndexedMediaQuality.isMedia(
            "https://example.org/release/torrent/123","Game release",null,"GAMES"))
    }
    @Test fun rejectsUndifferentiatedWebsite(){
        assertFalse(IndexedMediaQuality.isMedia(
            "https://example.org/site","Site listing",
            "https://example.org/preview.jpg","ADULT"))
    }
}
