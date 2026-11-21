package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/** Kiutaku remains native to Kyora; only the unified Cosplay image bridge
 * is changed. The tests protect full-resolution previews and gallery ownership.
 */
class KiutakuPreviewNativeBridgeTest {
    private val album=IndexedItem("https://kiutaku.com/7440/","A gallery",
        null,"https://kiutaku.com/","COSPLAY","cosplay",0L,false)

    @Test fun largestNativeSrcsetImageSurvivesUnifiedCosplayPreview(){
        val doc=Jsoup.parse("""<article><div class="article-fulltext">
            <img src="https://mitaku.net/wp-content/uploads/2026/11/sample-320.jpg"
              srcset="https://mitaku.net/wp-content/uploads/2026/11/sample-320.jpg 320w,
               https://mitaku.net/wp-content/uploads/2026/11/sample-2048.jpg 2048w"/>
            </div><aside class="related">
            <img src="https://mitaku.net/wp-content/uploads/other.jpg"/>
            </aside></article>""","https://kiutaku.com/7440/")
        assertEquals("https://mitaku.net/wp-content/uploads/2026/11/sample-2048.jpg",
            KyoraCreatorPreviews.selectedKiutakuImage(doc,album))
    }

    @Test fun onlyRealNumericKiutakuAlbumCanProvidePhoto(){
        val doc=Jsoup.parse("""<main><img
            data-original="https://mitaku.net/wp-content/uploads/2026/11/picture.jpg"/></main>""",
            "https://kiutaku.com/tag/123/")
        assertNull(KyoraCreatorPreviews.selectedKiutakuImage(
            doc,album.copy(url="https://kiutaku.com/tag/123/")))
    }

    @Test fun explicitPublisherOriginalAttributeStillWorks(){
        val doc=Jsoup.parse("""<main><article>
            <img data-original="https://static.kiutaku.com/photos/01.jpg"/>
            </article></main>""","https://kiutaku.com/7440/")
        assertEquals("https://static.kiutaku.com/photos/01.jpg",
            KyoraCreatorPreviews.selectedKiutakuImage(doc,album))
    }
}
