package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SitePreviewImageTest {
    @Test fun choosesRealLargestSrcsetImage() {
        val doc=Jsoup.parse(
            """<a href="/post/123"><img src="/small.jpg"
                srcset="/small.jpg 320w, /medium.jpg 720w, /large.jpg 1440w"></a>""",
            "https://example.com/"
        )
        val img=doc.selectFirst("img")!!
        assertEquals("https://example.com/large.jpg",SitePreviewImage.best(img))
    }

    @Test fun prioritizesExplicitFullSizeAsset() {
        val img=Jsoup.parse(
            """<img src="/thumb.jpg" data-original="/genuine.jpg">""",
            "https://example.com/").selectFirst("img")!!
        assertEquals("https://example.com/genuine.jpg",SitePreviewImage.best(img))
    }

    @Test fun rejectsNonHttpsOrInlineFakeCovers() {
        val img=Jsoup.parse(
            """<img src="data:image/png;base64,abc" data-src="http://bad.example/a.jpg">""",
            "https://example.com/").selectFirst("img")!!
        assertNull(SitePreviewImage.best(img))
    }

    @Test fun readsPictureSourceWhenImgHasOnlyTinyFallback() {
        val img=Jsoup.parse(
            """<picture><source srcset="/cover480.jpg 480w, /cover1200.jpg 1200w">
               <img src="/tiny.jpg"></picture>""",
            "https://example.com/").selectFirst("img")!!
        assertEquals("https://example.com/cover1200.jpg",SitePreviewImage.best(img))
    }
}
