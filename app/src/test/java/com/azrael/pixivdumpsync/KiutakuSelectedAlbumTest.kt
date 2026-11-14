package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class KiutakuSelectedAlbumTest {
    @Test fun numericThumbnailAlwaysMeansOneAlbumNotTheArtistTag(){
        val chosen=ApprovedCosplayActions.exactKiutakuAlbum(
            "https://kiutaku.com/7440","Selected set")
        assertEquals("gallery_only",chosen.kind)
        assertEquals("gallery:7440",chosen.stableId)
        assertEquals("https://kiutaku.com/7440",chosen.canonicalUrl)
    }
    @Test fun taggingAndSiteWideRoutesCannotBePassedAsAnAlbum(){
        assertThrows(IllegalArgumentException::class.java){
            ApprovedCosplayActions.exactKiutakuAlbum(
                "https://kiutaku.com/tag/1305","Not a gallery")
        }
    }
}
