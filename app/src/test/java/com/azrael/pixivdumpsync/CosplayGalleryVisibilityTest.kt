package com.azrael.pixivdumpsync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CosplayGalleryVisibilityTest {
    @Test fun selectedAlbumsAppearInLibraryAsWellAsSiteGalleries(){
        assertTrue(AsterionCosplayGallery.hasGalleryCards("site"))
        assertTrue(AsterionCosplayGallery.hasGalleryCards("gallery_only"))
        assertTrue(AsterionCosplayGallery.hasGalleryCards("selected_album"))
    }

    @Test fun catalogSourcesAndTagsDoNotBecomeDownloadedAlbums(){
        assertFalse(AsterionCosplayGallery.hasGalleryCards("tag"))
        assertFalse(AsterionCosplayGallery.hasGalleryCards("category"))
    }
}
