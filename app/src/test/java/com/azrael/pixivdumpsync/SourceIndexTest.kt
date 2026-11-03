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
}
