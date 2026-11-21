package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class KyoraPreviewSourceIsolationTest {
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
