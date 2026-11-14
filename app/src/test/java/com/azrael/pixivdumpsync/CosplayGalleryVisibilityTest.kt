package com.azrael.pixivdumpsync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CosplayGalleryVisibilityTest {
    @Test fun selectedAlbumsAppearInLibraryAsWellAsSiteGalleries(){
        assertTrue(CosplayGalleryVisibility.hasGalleryCards("site"))
        assertTrue(CosplayGalleryVisibility.hasGalleryCards("gallery_only"))
        assertTrue(CosplayGalleryVisibility.hasGalleryCards("selected_album"))
    }

    @Test fun catalogSourcesAndTagsDoNotBecomeDownloadedAlbums(){
        assertFalse(CosplayGalleryVisibility.hasGalleryCards("tag"))
        assertFalse(CosplayGalleryVisibility.hasGalleryCards("category"))
    }
}
