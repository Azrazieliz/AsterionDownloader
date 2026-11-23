package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class KyoraPreviewSourceIsolationTest {
    @Test fun galleryDnsAnd4kGirlEmptyJsCatalogAreNotCachedForHours(){
        val source="https://4kgirl.com/"
        val hour=60L*60*1000
        assertEquals(12L*hour,KyoraCreatorPreviews.retryDelayMs(source,null))
        assertEquals(30_000L,KyoraCreatorPreviews.retryDelayMs(
            source,"UnknownHostException: Unable to resolve host 4kgirl.com"))
        assertEquals(30_000L,KyoraCreatorPreviews.retryDelayMs(
            source,"Publisher DNS lookup failed"))
        assertEquals(90_000L,KyoraCreatorPreviews.retryDelayMs(
            source,"No gallery thumbnails exposed by listing"))
        assertEquals(30L*60*1000,KyoraCreatorPreviews.retryDelayMs(
            source,"HTTP 403 Forbidden; authorized publisher session required"))
        assertEquals(2L*hour,KyoraCreatorPreviews.retryDelayMs(
            "https://4khd.com/","Site original identity unavailable"))
        assertEquals(5L*60*1000,KyoraCreatorPreviews.retryDelayMs(
            "https://kiutaku.com/","Unrecognized gallery markup"))
    }

    @Test fun fourKGirlGridwpSiblingLazyCoverIsIndexedAsOneRealAlbum(){
        val source="https://www.4kgirl.com/"
        val html="""<main><div class="gridwp-grid-post">
            <div class="post-thumbnail"><img
                src="/wp-content/themes/theme/loading.gif"
                data-lazy-src="/wp-content/uploads/2026/11/original-cover.jpg"
                alt="Verified publisher image"></div>
            <h2><a href="/first-party-cosplay-set/">First party set</a></h2>
            </div>
            <div class="gridwp-grid-post"><a href="/category/cosplay/">
                <img src="/wp-content/uploads/2026/11/category-cover.jpg"></a>
            </div>
            <div class="gridwp-grid-post"><a href="https://4kgirl.com.evil.example/fake-set/">
                <img src="https://4kgirl.com.evil.example/img.jpg"></a>
            </div></main>"""
        val doc=org.jsoup.Jsoup.parse(html,source)
        val entries=SiteBackfillProfiles.entries(
            doc,source,"COSPLAY",100L).orEmpty()
        assertEquals(1,entries.size)
        assertEquals("https://www.4kgirl.com/first-party-cosplay-set/",
            entries.single().url)
        assertEquals("First party set",entries.single().title)
        assertEquals("https://www.4kgirl.com/wp-content/uploads/2026/11/original-cover.jpg",
            entries.single().preview)
        val tiles=KyoraCreatorPreviews.parse(html,source)
        assertEquals(1,tiles.size)
        assertEquals(entries.single().url,tiles.single().url)
        assertEquals(entries.single().preview,tiles.single().cover)
    }

    @Test fun differentPublishersCannotShareAlbumCards(){
        val fourKhd="https://www.4khd.com/content/12345/gallery.html"
        val buondua="https://buondua.net/albums/genuine-gallery"
        val cosplayTele="https://cosplaytele.com/album/genuine-gallery"
        assertTrue(KyoraCreatorPreviews.previewBelongsToSource(
            "https://www.4khd.com/",fourKhd))
        assertTrue(KyoraCreatorPreviews.previewBelongsToSource(
            "https://buondua.net/",buondua))
        assertTrue(KyoraCreatorPreviews.previewBelongsToSource(
            "https://cosplaytele.com/",cosplayTele))
        assertFalse(KyoraCreatorPreviews.previewBelongsToSource(
            "https://buondua.net/",fourKhd))
        assertFalse(KyoraCreatorPreviews.previewBelongsToSource(
            "https://cosplaytele.com/",fourKhd))
        assertFalse(KyoraCreatorPreviews.previewBelongsToSource(
            "https://www.4khd.com/",cosplayTele))
        assertFalse(KyoraCreatorPreviews.previewBelongsToSource(
            "https://cosplaytele.com/",buondua))
    }

    @Test fun fakeOrMissingCachedPagesCannotBeTreatedAsProviderAlbums(){
        val source="https://cosplaytele.com/"
        assertFalse(KyoraCreatorPreviews.previewBelongsToSource(source,""))
        assertFalse(KyoraCreatorPreviews.previewBelongsToSource(
            source,"https://cosplaytele.com.evil.example/gallery"))
        assertFalse(KyoraCreatorPreviews.previewBelongsToSource(
            source,"http://cosplaytele.com/gallery"))
        assertFalse(KyoraCreatorPreviews.previewBelongsToSource(
            source,"file:///data/user/0/private.png"))
        assertTrue(KyoraCreatorPreviews.previewBelongsToSource(
            source,"https://www.cosplaytele.com/gallery/123"))
    }
    @Test fun buonDuaApprovedCatalogAliasRetainsNativeOriginals(){
        val catalog="https://buondua.com/"
        val original="https://buondua.net/albums/genuine-gallery"
        assertTrue(KyoraCreatorPreviews.previewBelongsToSource(catalog,original))
        assertTrue(KyoraCreatorPreviews.previewBelongsToSource(original,
            "https://buondua.com/albums/genuine-gallery"))
        assertFalse(KyoraCreatorPreviews.previewBelongsToSource(catalog,
            "https://www.4khd.com/content/12345/other.html"))
        assertFalse(KyoraCreatorPreviews.previewBelongsToSource(catalog,
            "https://buondua.net.evil.example/albums/stolen"))
    }

}
