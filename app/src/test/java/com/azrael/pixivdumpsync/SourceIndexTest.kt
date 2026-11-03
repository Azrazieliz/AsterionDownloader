package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class SourceIndexTest {
    @Test fun approvedIndexDoesNotAcceptLocalOrForeignLinks(){
        assertFalse(SourceIndexParser.safe("http://127.0.0.1/internal"))
        assertFalse(SourceIndexParser.safe("https://localhost/account"))
        assertFalse(SourceIndexParser.safe("https://192.168.1.2/data"))
        assertTrue(SourceIndexParser.safe("https://example.org/gallery/post-5"))
        val doc=Jsoup.parse("""<html><main>
          <a href="/post/story-123"><img src="/thumb.jpg">Story 123</a>
          <a href="https://outside.org/post/evil">Outside</a>
          <a href="/login">Login</a>
          <a href="/novels/chapter-1.epub">Novel EPUB</a>
        </main></html>""","https://example.org/")
        val items=SourceIndexParser.entries(doc,"https://example.org/","BOOKS",100L)
        assertTrue(items.any{it.kind=="epub"})
        assertFalse(items.any{it.url.contains("outside.org")||it.url.endsWith("/login")})
    }
    @Test fun onlyEpubIsAcceptedAsBookOriginal(){
        assertEquals("epub",SourceIndexParser.kind("https://x.org/books/a.epub","BOOKS"))
        assertEquals("archive",SourceIndexParser.kind("https://x.org/books/a.zip","BOOKS"))
        assertEquals("torrent",SourceIndexParser.kind("https://x.org/files/a.torrent","GAMES"))
    }
    @Test fun enginePlanDistinguishesRealFormats(){
        assertEquals(GamePortPlanner.Engine.RENPY,
            GamePortPlanner.detect(listOf("Game/game/script.rpy","Game/game/data.rpa")))
        assertEquals(GamePortPlanner.Engine.RPG_MAKER,
            GamePortPlanner.detect(listOf("www/index.html","www/js/rmmz_core.js")))
        assertEquals(GamePortPlanner.Engine.THREE_DS,
            GamePortPlanner.detect(listOf("mygame.3ds")))
        assertEquals(GamePortPlanner.Engine.WINDOWS,
            GamePortPlanner.detect(listOf("Game.exe")))
        assertEquals(GamePortPlanner.Engine.HTML5,
            GamePortPlanner.detect(listOf("game/index.html")))
    }
    @Test fun numericGalleryPaginationSkipsPostNumbers(){
        val doc=Jsoup.parse("""<html><nav class="pagination">
            <a href="/post/2">2</a><a href="/gallery?page=2">2</a>
            <a href="https://outside.org/gallery?page=2">2</a>
        </nav></html>""","https://example.org/gallery")
        assertEquals("https://example.org/gallery?page=2",
            SourceIndexParser.next(doc,"https://example.org/gallery",
                "https://example.org/gallery"))
    }
    @Test fun numericPageCursorResumesSequentially(){
        val doc=Jsoup.parse("""<div class="pager">
            <a href="/gallery?page=2">2</a>
            <a href="/gallery?page=3">3</a>
            <a href="/gallery?page=7">7</a>
        </div>""","https://example.org/gallery?page=2")
        assertEquals("https://example.org/gallery?page=3",
            SourceIndexParser.next(doc,"https://example.org/gallery",
                "https://example.org/gallery?page=2"))
    }
    @Test fun pathPaginationIsSupportedAndUnrelatedLinksIgnored(){
        val doc=Jsoup.parse("""<nav class="pagination">
            <a href="/post/482">482</a>
            <a href="/gallery/page/4/">4</a>
        </nav>""","https://example.org/gallery/page/3/")
        assertEquals("https://example.org/gallery/page/4/",
            SourceIndexParser.next(doc,"https://example.org/gallery",
                "https://example.org/gallery/page/3/"))
        val empty=Jsoup.parse("<nav class='pagination'><a href='/post/4'>4</a></nav>",
            "https://example.org/gallery")
        assertNull(SourceIndexParser.next(empty,"https://example.org/gallery",
            "https://example.org/gallery"))
    }
    @Test fun explicitNextRemainsCompatible(){
        val doc=Jsoup.parse("""<link rel="next" href="/gallery?page=2">""",
            "https://example.org/gallery")
        assertEquals("https://example.org/gallery?page=2",
            SourceIndexParser.next(doc,"https://example.org/gallery",
                "https://example.org/gallery"))
    }

    @Test fun canonicalProviderHostsAllowSafeMobileLocaleAndWww(){
        assertTrue(SourceIndexParser.sameSite("https://fr.xvideos.com/",
            "https://www.xvideos.com/album?page=2"))
        assertTrue(SourceIndexParser.sameSite("https://www.example.org/",
            "https://m.example.org/post/8"))
        assertFalse(SourceIndexParser.sameSite("https://example.org",
            "https://example.org.attacker.invalid/post/8"))
        assertFalse(SourceIndexParser.sameSite("https://example.org",
            "https://evil.example.net/post/8"))
    }

    @Test fun responsivePreviewExtractedFromSrcset(){
        val page=Jsoup.parse("""<article>
          <a href="/post/342"><img srcset="/media/card-342.webp 2x, /media/card-low.jpg 1x">New creator post</a>
        </article>""","https://example.org/")
        val hits=SourceIndexParser.entries(page,"https://example.org/","COSPLAY",100L)
        assertEquals(1,hits.size)
        assertEquals("https://example.org/media/card-342.webp",hits[0].preview)
    }

    @Test fun originalVideoSourceCanBeDiscoveredWithoutAnchor(){
        val doc=Jsoup.parse("""<main><article><h2>Creator clip</h2>
          <video poster="/media/clip-cover.webp">
            <source src="https://cdn.example.org/video/clip.mp4" type="video/mp4">
          </video></article></main>""","https://example.org/")
        val hits=SourceIndexParser.entries(doc,"https://example.org/","VIDEOS",100L)
        assertEquals(1,hits.size)
        assertEquals("Creator clip",hits[0].title)
        assertEquals("video",hits[0].kind)
        assertEquals("https://cdn.example.org/video/clip.mp4",hits[0].url)
        assertEquals("https://example.org/media/clip-cover.webp",hits[0].preview)
        assertTrue(SourceIndexParser.entries(doc,"https://example.org/","BOOKS",100L).isEmpty())
    }

}
