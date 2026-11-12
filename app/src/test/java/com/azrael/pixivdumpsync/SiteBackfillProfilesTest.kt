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
    @Test fun sushiscanFindsSeriesWithoutMistakingChapterPageAsSeries(){
        val base="https://sushiscan.net/catalogue/"
        val result=titles("""<main>
          <a href="/catalogue/black-clover/">Black Clover</a>
          <a href="/black-clover-chapitre-392/">Chapter 392</a>
        </main>""",base,"COMICS")
        assertEquals(1,result.size)
        assertTrue(SushiScanChapters.isSeries(result.single().url))
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

}
