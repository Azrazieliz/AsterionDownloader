package com.azrael.galleryflow

import com.azrael.pixivdumpsync.SiteBackfillProfiles
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test

class FourKGirlListingRecoveryTest {
    private val home="https://www.4kgirl.com/"
    private val post="https://www.4kgirl.com/artist-photo-set/"

    @Test fun wordpressGridImageOnlyCardsAreRealAlbumsNotCategories() {
        val fake=object:HttpClient(){
            override fun document(url:String,referer:String?):Document{
                assertEquals(home,url)
                return Jsoup.parse("""
                    <main><div class="gridwp-grid-post">
                      <a href="/artist-photo-set/">
                        <img alt="Artist photo set" data-src="/cover.webp"/>
                      </a>
                      <a href="/category/cosplay/"><img src="/category.webp"/></a>
                    </div></main>
                """.trimIndent(),url)
            }
        }
        val adapter=FourKGirlAdapter(fake)
        val albums=adapter.enumerateGalleries(adapter.defaultEntities().single()).toList()
        assertEquals(1,albums.size)
        assertEquals(post,albums.single().canonicalUrl)
        assertEquals("Artist photo set",albums.single().title)
    }

    @Test fun siteIndexUsesPublisherResponsiveCoverNotLoadingPlaceholder() {
        val doc=Jsoup.parse("""
            <main><article class="post">
              <a href="/artist-photo-set/"><img alt="Artist photo set"
                src="/loading.gif"
                srcset="/gallery-200.webp 200w, /gallery-1200.webp 1200w"/>
              </a>
            </article></main>
        """.trimIndent(),home)
        val entries=SiteBackfillProfiles.entries(doc,home,"COSPLAY",123L)
        assertNotNull(entries)
        assertEquals(1,entries!!.size)
        assertEquals(post,entries.single().url)
        assertEquals("https://www.4kgirl.com/gallery-1200.webp",entries.single().preview)
    }
}
