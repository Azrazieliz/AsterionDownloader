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
        assertEquals(GamePortPlanner.Engine.UNITY_WINDOWS,
            GamePortPlanner.detect(listOf("Game.exe","UnityPlayer.dll",
                "Game_Data/globalgamemanagers")))
        assertEquals(GamePortPlanner.Engine.UNREAL_WINDOWS,
            GamePortPlanner.detect(listOf("Game.exe","Engine/Content/Paks/game.pak")))
        assertEquals(GamePortPlanner.Engine.RPG_MAKER_LEGACY,
            GamePortPlanner.detect(listOf("Game.exe","Game.rgss3a","Data/Map001.rvdata2")))
        assertEquals(GamePortPlanner.Engine.GODOT_EXPORT,
            GamePortPlanner.detect(listOf("game.pck","game.exe")))
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

    @Test fun postDetailResolvesOnlyAccessibleOriginalFormats(){
        val doc=Jsoup.parse("""<html><main><article>
            <a href="https://cdn.example.org/files/story.epub">Download EPUB</a>
            <a href="/media/sample.mp4">Teaser</a>
            <a href="/files/chapter.zip">Chapter ZIP</a>
            <a href="/files/chapter.cbz">CBZ</a>
            <img src="/media/thumb.jpg">
        </article></main></html>""","https://example.org/post/100")
        fun source(area:String)=IndexedItem("https://example.org/post/100",
            "Series 100",null,"https://example.org/",area,area.lowercase(),10L,false)
        val book=SourceIndexParser.detailOriginals(doc,source("BOOKS"),100L)
        assertEquals(listOf("https://cdn.example.org/files/story.epub"),
            book.map{it.url})
        val comic=SourceIndexParser.detailOriginals(doc,source("COMICS"),100L)
        assertEquals(listOf("https://example.org/files/chapter.zip"),
            comic.map{it.url})
        val video=SourceIndexParser.detailOriginals(doc,source("VIDEOS"),100L)
        assertTrue(video.isEmpty())
    }

    @Test fun autoAcquisitionKeepsEpubOnlyAndComicZipsOnly(){
        fun eligible(area:String,kind:String,filename:String):Boolean =
            SourceIndexParser.autoOriginal(IndexedItem(
                "https://example.org/files/"+filename,"Test Original",null,
                "https://example.org",area,kind,1L,false))
        assertTrue(eligible("BOOKS","epub","volume.epub"))
        assertFalse(eligible("BOOKS","archive","volume.zip"))
        assertFalse(eligible("BOOKS","archive","volume.pdf"))
        assertTrue(eligible("COMICS","archive","chapter-10.zip"))
        assertFalse(eligible("COMICS","archive","chapter-10.cbz"))
        assertFalse(eligible("COMICS","archive","chapter-10.rar"))
        assertTrue(eligible("ASSETS","archive","models.zip"))
        assertTrue(eligible("GAMES","apk","game.apk"))
        assertTrue(eligible("VIDEOS","video","full.mp4"))
        assertFalse(eligible("ARTWORK","image","preview.jpg"))
    }

    @Test fun creatorPostDownloadsExplicitAttachmentsNotPreviewImages(){
        val doc=Jsoup.parse("""<html><main><article>
            <a href="/images/thumb.jpg"><img src="/images/thumb-small.jpg">Preview</a>
            <a href="/images/sample.jpg">Sample portrait</a>
            <div class="attachments">
              <a href="/files/complete-artwork.png">Original illustration</a>
              <a href="/files/creator-pack.zip">Complete image pack</a>
            </div>
            <a download href="/files/character.glb">Character model</a>
        </article></main></html>""","https://example.org/post/124")
        val source=IndexedItem("https://example.org/post/124","Creator work",
            null,"https://example.org/","CREATORS","creators",10L,false)
        val originals=SourceIndexParser.detailOriginals(doc,source,100L)
        assertEquals(setOf("https://example.org/files/complete-artwork.png",
            "https://example.org/files/creator-pack.zip",
            "https://example.org/files/character.glb"),originals.map{it.url}.toSet())
        assertTrue(originals.all{it.kind=="original" &&
            SourceIndexParser.autoOriginal(it)})
        val listing=SourceIndexParser.entries(doc,"https://example.org/",
            "CREATORS",100L)
        assertTrue(listing.none{it.kind=="original"})
        assertTrue(listing.none{it.kind=="image" &&
            SourceIndexParser.autoOriginal(it)})
    }

    @Test fun creatorDownloadButtonsExposeOriginalsWithoutOrdinaryLinks(){
        val doc=Jsoup.parse("""<main><article>
            <button data-download-url="/files/illustrations.zip"
                data-filename="illustrations.zip">Download pack</button>
            <div data-file-url="/files/high-res.jpg"
                data-filename="high-res.jpg">Original image</div>
            <img src="/images/poster.jpg">
        </article></main>""","https://example.org/post/125")
        val source=IndexedItem("https://example.org/post/125","Creator work",
            null,"https://example.org/","CREATORS","creators",10L,false)
        val files=SourceIndexParser.detailOriginals(doc,source,100L)
        assertEquals(2,files.size)
        assertTrue(files.all{it.kind=="original"})
        assertFalse(files.any{it.url.endsWith("poster.jpg")})
    }

    @Test fun genericCosplayDetailResolvesExplicitFullSizeImagesOnly(){
        val doc=Jsoup.parse("""<main><article>
          <a href="/album/40"><img src="/images/preview.jpg">Cover</a>
          <div class="gallery">
            <img src="/images/thumb-a.jpg" data-original="/images/full-a.jpg">
            <img src="/images/thumb-b.jpg" data-full="/images/full-b.png">
            <a download href="/albums/complete.zip">Complete album</a>
          </div>
        </article></main>""","https://example.org/album/40")
        val src=IndexedItem("https://example.org/album/40","Album 40",null,
            "https://example.org/","COSPLAY","cosplay",10L,false)
        val files=SourceIndexParser.detailOriginals(doc,src,100L)
        assertEquals(setOf("https://example.org/images/full-a.jpg",
            "https://example.org/images/full-b.png",
            "https://example.org/albums/complete.zip"),files.map{it.url}.toSet())
        assertTrue(files.all{it.kind=="original" && SourceIndexParser.autoOriginal(it)})
        assertFalse(files.any{it.url.contains("thumb")||it.url.contains("preview")})
    }

    @Test fun genericGalleryListingImagesAreNotMistakenForOriginals(){
        val doc=Jsoup.parse("""<main><article>
          <a href="/albums/1"><img src="/images/thumb-1.jpg">Album One</a>
        </article></main>""","https://example.org/")
        val indexed=SourceIndexParser.entries(doc,"https://example.org/","COSPLAY",100L)
        assertEquals(1,indexed.size)
        assertFalse(indexed.any{SourceIndexParser.autoOriginal(it)})
        val detail=SourceIndexParser.detailOriginals(doc,IndexedItem(
            "https://example.org/albums/1","Album One",null,
            "https://example.org/","COSPLAY","cosplay",10L,false),100L)
        assertTrue(detail.isEmpty())
    }

    @Test fun signedCreatorAttachmentUsesExplicitFilenameForType(){
        val doc=Jsoup.parse("""<main><article>
            <a download="artwork-pack.zip" href="https://cdn.example.org/file/9271?token=a1">
                Download pack</a>
        </article></main>""","https://example.org/post/789")
        val src=IndexedItem("https://example.org/post/789","Creator release",null,
            "https://example.org/","CREATORS","creators",10L,false)
        val files=SourceIndexParser.detailOriginals(doc,src,100L)
        assertEquals(1,files.size)
        assertEquals("artwork-pack.zip",files[0].title)
        assertEquals("original",files[0].kind)
        assertTrue(SourceIndexParser.autoOriginal(files[0]))
    }
    @Test fun signedBookAndComicDownloadControlsRetainOutputFormats(){
        val html="""<main><article>
            <a download="book.epub" href="https://cdn.example.org/api/file/1">Book</a>
            <a download="chapter.zip" href="https://cdn.example.org/api/file/2">Chapter</a>
        </article></main>"""
        fun source(area:String)=IndexedItem("https://example.org/post/2","Work",
            null,"https://example.org/",area,area.lowercase(),10L,false)
        val books=SourceIndexParser.detailOriginals(
            Jsoup.parse(html,"https://example.org/post/2"),source("BOOKS"),100L)
        val comics=SourceIndexParser.detailOriginals(
            Jsoup.parse(html,"https://example.org/post/2"),source("COMICS"),100L)
        assertEquals(listOf("book.epub"),books.map{it.title})
        assertEquals(listOf("chapter.zip"),comics.map{it.title})
    }

}
