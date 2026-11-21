package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class SiteBackfillProfilesTest {
    private fun titles(html:String,base:String,area:String):List<IndexedItem> =
        SiteBackfillProfiles.entries(Jsoup.parse(html,base),base,area,1234L).orEmpty()

    @Test fun freewebnovelOnlyEnqueuesSeriesNotIndividualChapters(){
        val base="https://freewebnovel.com/"
        val records=titles("""<main>
          <a href="/novel/i-am-the-fated-villain">I Am The Fated Villain</a>
          <a href="/novel/i-am-the-fated-villain/chapter-1">Chapter 1</a>
          <a href="/login">Login</a>
        </main>""",base,"BOOKS")
        assertEquals(1,records.size)
        assertEquals("books",records.single().kind)
        assertTrue(NovelEpubParser.supportedSeries(records.single().url))
    }
    @Test fun novelbuddyAndReadnovelfullGetIndependentBookListings(){
        val buddy="https://novelbuddy.com/home"
        val b=titles("""<div class="novel-item"><a href="/novel/shadow-slave">
          <img alt="Shadow Slave"/> </a></div>""",buddy,"BOOKS")
        assertEquals("https://novelbuddy.com/novel/shadow-slave",b.single().url)
        val full="https://readnovelfull.com/"
        val f=titles("""<div class="book-item">
           <a href="/book/a-novel">A Novel</a>
           <a href="/account">Log in</a></div>""",full,"BOOKS")
        assertEquals(1,f.size)
        assertEquals("books",f.single().kind)
    }
    @Test fun retiredSushiScanCannotBeAutoBackfilledAsNewFrenchSource(){
        val base="https://sushiscan.net/catalogue/"
        val result=titles("""<main>
          <a href="/catalogue/black-clover/">Black Clover</a>
          <a href="/black-clover-chapitre-392/">Chapter 392</a>
        </main>""",base,"COMICS")
        assertTrue(result.isEmpty())
    }
    @Test fun bothNhentaiSitesKeepCorrectGalleryIds(){
        for(host in listOf("nhentai.net","nhentai.xxx")){
            val base="https://$host/language/english/"
            val records=titles("""<div id="gallery">
              <a href="/g/12345/"><img alt="Collection"/></a>
              <a href="/g/12345/1/"><img alt="Page one"/></a>
            </div>""",base,"COMICS")
            assertEquals(host,1,records.size)
            assertEquals(host,true,NhentaiChapters.isGallery(records.single().url))
        }
    }
    @Test fun kiutakuRecognizesNumericGalleryPostsAndOriginalMedia(){
        val home="https://kiutaku.com/"
        val records=titles("""<main><a href="/4495">
            <img alt="Alina Becker Tracer"/></a></main>""",home,"COSPLAY")
        assertEquals(1,records.size)
        val page="https://kiutaku.com/4495/"
        val doc=Jsoup.parse("""<main><article><div class="content">
           <a href="https://static.kiutaku.com/photos/01.jpg"><img src="/assets/thumb01.jpg"/></a>
           <img data-original="https://static.kiutaku.com/photos/02.jpg"/>
           <aside><img src="/static/avatar.jpg"/></aside>
        </div></article></main>""",page)
        val originals=SiteBackfillProfiles.galleryOriginals(doc,records.single(),0L)
        assertEquals(2,originals.size)
        assertTrue(originals.all{it.kind=="original"})
    }
    @Test fun cosplayteleWordpressPostAndPaginatorRecognized(){
        val base="https://cosplaytele.com/"
        val doc=Jsoup.parse("""<article>
            <h2><a href="/chen-hai/">Hualing Chen Hai</a></h2>
          </article><nav class="pagination">
            <a class="next" href="/page/2/">Older Posts</a>
          </nav>""",base)
        assertEquals(1,SiteBackfillProfiles.entries(doc,base,"COSPLAY",0L)!!.size)
        assertEquals(base+"page/2/",SiteBackfillProfiles.next(doc,base,base))
    }
    @Test fun nativeSingleSlugAndNumericCosplayGalleriesSurviveGenericMediaFiltering(){
        val tele="https://cosplaytele.com/"
        val telePage=Jsoup.parse("""<article class="post-card">
           <h2><a href="/scheherazade/">
             <img src="https://cosplaytele.com/wp-content/uploads/photo.webp"
                  alt="Scheherazade cosplay photo collection"/>
             Scheherazade Cosplay Collection</a></h2></article>""",tele)
        val cards=SourceIndexParser.entries(telePage,tele,"COSPLAY",100L)
        assertEquals("Valid single-slug native gallery must not be discarded",
            1,cards.size)
        assertEquals("https://cosplaytele.com/scheherazade/",cards.single().url)
        val kiutaku="https://kiutaku.com/"
        val kiutakuPage=Jsoup.parse("""<main><article class="post-card">
            <a href="/4495"><img src="https://kiutaku.com/photo.webp"
                 alt="Original cosplay photo gallery"/></a>
            </article></main>""",kiutaku)
        val numeric=SourceIndexParser.entries(kiutakuPage,kiutaku,"COSPLAY",100L)
        assertEquals(1,numeric.size)
        assertEquals("https://kiutaku.com/4495/",numeric.single().url)
    }
    @Test fun nativeGalleryPathRejectsExternalOrNavigationLinks(){
        val home="https://cosplaytele.com/"
        val page=Jsoup.parse("""<main>
           <a href="/login/">Log in</a>
           <a href="https://unknown.invalid/other-post/">Off-site gallery</a>
           <a href="/page/5/">Navigation</a>
           </main>""",home)
        assertTrue(SourceIndexParser.entries(page,home,"COSPLAY",100L).isEmpty())
    }
    @Test fun retainedKyoraProvidersRecognizeOnlyOriginalGalleryPages(){
        val cases=listOf(
            "https://www.4khd.com/" to
                "https://www.4khd.com/content/20/set-name.html",
            "https://buondua.com/" to
                "https://buondua.net/albums/album-123/",
            "https://cosplaytele.com/" to
                "https://cosplaytele.com/selected-gallery/",
            "https://kiutaku.com/" to
                "https://kiutaku.com/7440/",
            "https://pimpbunny.com/models/" to
                "https://pimpbunny.com/models/creator-1/photos/set-12/")
        for((source,album) in cases)
            assertTrue("$source should own $album",
                SiteBackfillProfiles.recognizesGalleryPage(source,album))
        for(retired in listOf(
            "https://www.pornpics.com/galleries/example/",
            "https://celeb.st/creator/sample-123/images")){
            assertFalse(ProviderRetirement.isRetired("https://pimpbunny.com/models/"))
            assertTrue(ProviderRetirement.isRetired(retired))
            assertFalse(SiteBackfillProfiles.supports(SiteControls.key(retired)))
        }
        assertFalse(SiteBackfillProfiles.recognizesGalleryPage(
            "https://pimpbunny.com/models/",
            "https://pimpbunny.com/models/some-creator/"))
        assertFalse(SiteBackfillProfiles.recognizesGalleryPage(
            "https://pimpbunny.com/models/",
            "https://pimpbunny.com/videos/scene-title/"))
        assertFalse(SiteBackfillProfiles.recognizesGalleryPage(
            "https://cosplaytele.com/","https://cosplaytele.com/category/"))
        assertFalse(SiteBackfillProfiles.recognizesGalleryPage(
            "https://buondua.com/","https://buondua.net/search"))
    }

    @Test fun pimpbunnyModelPortraitsAreNotPromotedToPhotoAlbums(){
        val source="https://pimpbunny.com/models/"
        val html="""<main><article class="model">
          <a href="/models/lolabunny-yu/">
            <img src="https://pimpbunny.com/posters/lolabunny.webp"
                 alt="Lolabunny profile portrait"/></a>
          <div class="photo-grid"><a href="/photos/model-set-123/">
            <img src="https://pimpbunny.com/previews/set-123.webp"
                 alt="Model photos set"/></a></div>
          <div class="video-card"><a href="/videos/actual-scene-123/">
            <img src="https://pimpbunny.com/posters/video.webp"
                 alt="Video scene"/></a></div></article></main>"""
        val records=SourceIndexParser.entries(
            Jsoup.parse(html,source),source,"COSPLAY",100L)
        assertEquals(1,records.size)
        assertEquals("https://pimpbunny.com/photos/model-set-123/",
            records.single().url)
        assertEquals("cosplay",records.single().kind)
        val portraitsOnly=SourceIndexParser.entries(
            Jsoup.parse("""<main><article>
                <a href="/models/lolabunny-yu/">
                  <img src="/portrait.webp" alt="Creator portrait"/></a>
                </article></main>""",source),source,"COSPLAY",100L)
        assertTrue("A model portrait is not a published photo album",
            portraitsOnly.isEmpty())
    }

    @Test fun otherProvidersStillUseOriginalGenericPath(){
        assertNull(SiteBackfillProfiles.entries(
            Jsoup.parse("<a href='/something'>Example</a>","https://other.example/"),
            "https://other.example/","ASSETS",0L))
    }
    @Test fun archivebateLegalNoticeCannotBecomeOriginal(){
        val home="https://archivebate.com/"
        val doc=Jsoup.parse("""<main><a href="/2257">
            USC 2257 Statement</a>
            <a href="/platform/eW91dHViZQ==">Platform</a></main>""",home)
        assertTrue(SourceIndexParser.entries(doc,home,"ADULT",1234L).isEmpty())
    }
    @Test fun kiutakuArticleHighResolutionInlineImagesAreOriginals(){
        val home="https://kiutaku.com/"
        val record=IndexedItem(home+"7440/","Gallery",null,home,"COSPLAY",
            "cosplay",1234L,false)
        val doc=Jsoup.parse("""<main><article><div class="entry-content">
           <p><img src="https://mitaku.net/wp-content/uploads/2026/11/photo-1.jpg"
               width="768" height="1024" alt="Photo one"/></p>
           <p><img src="https://mitaku.net/wp-content/uploads/2026/11/photo-2.jpg"
               width="1024" height="768" alt="Photo two"/></p>
           <img src="https://mitaku.net/wp-content/uploads/2026/11/thumb.jpg"
               width="200" height="150"/>
           <aside><img src="https://kiutaku.com/sidebar.jpg"
               width="800" height="800"/></aside>
           </div></article></main>""",record.url)
        val originals=SiteBackfillProfiles.galleryOriginals(doc,record,1234L)
        assertEquals(2,originals.size)
        assertTrue(originals.all{it.kind=="original"})
        assertTrue(originals.all{it.url.contains("mitaku.net")})
    }

    @Test fun kiutakuNonArticleBodyAndDirectMitakuLinksAreRealPhotos(){
        val home="https://kiutaku.com/"
        val item=IndexedItem(home+"7440/","Real gallery",null,home,"COSPLAY",
            "cosplay",123L,false)
        val doc=Jsoup.parse("""<main id="main">
           <div id="content"><div class="post-body">
             <a href="https://mitaku.net/wp-content/uploads/2026/11/photo-a.jpg">Open photo</a>
             <p><img loading="lazy"
               data-src="https://mitaku.net/wp-content/uploads/2026/11/photo-b.webp"
               src="/placeholder.gif" width="768" height="1024"/></p>
           </div></div>
           <aside><img src="https://kiutaku.com/logo.jpg"/></aside></main>""",
           item.url)
        val photos=SiteBackfillProfiles.galleryOriginals(doc,item,123L)
        assertEquals(2,photos.size)
        assertTrue(photos.all{it.url.contains("mitaku.net/wp-content/uploads")})
    }

    @Test fun mp4HentaiHomepageExtractsDatedRealPostsNotReleaseCalendar(){
        val base="https://mp4hentai.com/"
        val html="""<main>
           <article class="post">
             <h2 class="entry-title"><a
               href="/2025/03/mari-bunny-bot-r18/">Mari – Bunny Bot R18</a></h2>
             <a class="post-thumbnail" href="/2025/03/mari-bunny-bot-r18/">
               <img src="https://mp4hentai.com/uploads/bunny.webp"
                    alt="Mari Bunny Bot" /></a>
           </article>
           <nav><a href="/category/upcoming-hentai-release/">Upcoming</a></nav>
           </main>"""
        val records=titles(html,base,"HENTAI")
        assertTrue("Retired MP4Hentai must never reappear via old backfill",records.isEmpty())
        assertFalse(SiteBackfillProfiles.supports("mp4hentai.com"))
    }

    @Test fun mp4HentaiGridwpListingMatchesRealHomeMarkup(){
        val base="https://mp4hentai.com/"
        val html="""<section class="gridwp-grid-posts">
         <div class="gridwp-grid-post">
          <div class="gridwp-grid-post-thumbnail">
            <a href="https://mp4hentai.com/2025/03/mari-bunny-bot-r18/">
             <img src="https://mp4hentai.com/wp-content/uploads/cover.webp"
                  alt="Mari Bunny Bot" /></a>
          </div>
          <h3 class="gridwp-grid-post-title"><a
              href="https://mp4hentai.com/2025/03/mari-bunny-bot-r18/">
              Mari – Bunny Bot R18</a></h3>
         </div>
         <h3><a href="/2026/10/upcoming-november-2026-hentai-release/">
             Upcoming November 2026 Release</a></h3>
        </section>"""
        val cards=titles(html,base,"HENTAI")
        assertTrue("Retired MP4Hentai grid must not reseed video cards",cards.isEmpty())
    }

}
