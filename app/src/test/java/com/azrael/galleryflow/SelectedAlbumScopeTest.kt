package com.azrael.galleryflow

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class SelectedAlbumScopeTest {
    @Test fun wordpressAlbumUsesOnlyTheSelectedPostsGallery() {
        val selected="https://cosplaytele.com/literal-selected-set/"
        val doc=Jsoup.parse("""
            <article id="post-532484" class="post-532484">
              <div class="article-inner"><div class="entry-content single-page">
                <img src="https://cdn.cosplaytele.com/cover.jpg">
                <div class="gallery galleryid-532484">
                  <figure><a href="/files/original.jpg">
                    <picture><source srcset="/files/reencoded.webp 2048w">
                      <img src="/files/display.jpg">
                    </picture>
                  </a></figure>
                </div>
                <div class="gallery galleryid-123">
                  <img src="/files/other-album.jpg">
                </div>
              </div></div>
            </article>
        """,selected)
        assertEquals(listOf("https://cosplaytele.com/files/original.jpg"),
            SelectedAlbumScope.originals(doc,selected).map{it.url})
    }

    @Test fun declared37PhotoAlbumNeverAbsorbs17RecommendedPhotos() {
        val photos=(1..37).joinToString(""){i->
            "<img src='https://cdn.cosplaytele.com/set/$i.jpg' alt='photo'/>"
        }
        val related=(1..17).joinToString(""){i->
            "<img src='https://cdn.cosplaytele.com/related/$i.jpg' alt='photo'/>"
        }
        val doc=Jsoup.parse("""
            <html><body><article><div class="entry-content">$photos
            <aside class="related">$related</aside></div></article></body></html>
        """.trimIndent(),"https://cosplaytele.com/post/one/")
        val extracted=SelectedAlbumScope.originals(
            doc,"https://cosplaytele.com/post/one/")
        assertEquals(37,extracted.size)
        assertTrue(extracted.all{"/set/" in it.url})
        assertEquals(37,SelectedAlbumScope.advertisedCount(
            "linhi_cute Complete [3.88GB-37photos]"))
        SelectedAlbumScope.verifyAdvertisedCount(37,extracted.size)
    }

    @Test fun countMismatchFailsBeforeTransferringEvenIfAllImagesLookValid(){
        val doc=Jsoup.parse("<article><div class='post-content'>"+
            (1..54).joinToString(""){i->
                "<img src='https://cdn.cosplaytele.com/$i.jpg' alt='photo'/>"
            }+"</div></article>","https://cosplaytele.com/post/one/")
        val images=SelectedAlbumScope.originals(
            doc,"https://cosplaytele.com/post/one/")
        assertEquals(54,images.size)
        assertThrows(AdapterException::class.java){
            SelectedAlbumScope.verifyAdvertisedCount(37,images.size)
        }
    }

    @Test fun unrecognisedPublisherLayoutCannotFallBackToWholePageAds(){
        val doc=Jsoup.parse("<main><div class='recommended'>"+
            "<img src='https://cdn.cosplaytele.com/other.jpg'></div></main>",
            "https://cosplaytele.com/post/one/")
        assertThrows(AdapterException::class.java){
            SelectedAlbumScope.originals(doc,
                "https://cosplaytele.com/post/one/")
        }
    }

    @Test fun fourKhdOnlyAcceptsPublisherFullHdBridgeNotRelatedPage(){
        val selected="https://www.4khd.com/content/14/yeha-yeha-pure2.html"
        val doc=Jsoup.parse("""
            <main><article class="entry-content">
              <p>Full HD without watermark download:
              <a href="https://m.4khd.com/45ycnl">TeraBox</a></p>
              <div class="related">
                <p><a href="https://m.4khd.com/other123">
                    TeraBox other album</a></p>
              </div>
              <nav><a href="/content/14/yeha-yeha-pure2.html/2">2</a></nav>
            </article></main>
        """,selected)
        assertEquals(listOf("https://m.4khd.com/45ycnl"),
            SelectedAlbumScope.officialOriginalArchives(doc,selected))
    }

    @Test fun fourKhdCannotMarkArticlePreviewAsOfficialArchive(){
        val selected="https://www.4khd.com/content/14/yeha-yeha-pure2.html"
        val doc=Jsoup.parse(
            "<main><article><img src='https://cdn.4khd.com/thumb.jpg'>"+
                "</article></main>",selected)
        assertTrue(SelectedAlbumScope.officialOriginalArchives(
            doc,selected).isEmpty())
    }

    @Test fun onlyKnownPublisherSitesGetStrictAlbumRules(){
        assertTrue(SelectedAlbumScope.owns("https://cosplaytele.com/post/one"))
        assertTrue(SelectedAlbumScope.owns("https://4khd.com/gallery/123"))
        assertFalse(SelectedAlbumScope.owns("https://evilcosplaytele.com/post/one"))
    }
}
