package com.azrael.galleryflow

import org.junit.Assert.*
import org.junit.Test

class CosplayStoragePolicyTest {
    private fun entity(kind:String="selected_album")=EntityRecord(SourceId.GENERIC,
        "entity1","Selected album title","https://photos.example.org/posts/1",
        kind,true,false,null,null,null)
    private fun gallery(creator:String?,date:String?)=GalleryMeta(SourceId.GENERIC,
        "galleryABC123","entity1","https://photos.example.org/posts/1",
        "Gallery: Artist/Set",emptyList(),date,emptyList(),creator)
    @Test fun organizesActualAuthorAndDateWithoutFakeLabels(){
        val path=CosplayStoragePolicy.relativePath(entity(),
            gallery("Cosplayer A","2026-11-13T10:30:00+01:00"))
        assertTrue(path.startsWith("Download/AsterionDownloader/Cosplay/photos.example.org/Cosplayer A/"))
        assertTrue(path.contains("2026-11-13_Gallery_ Artist_Set_galleryABC"))
        assertFalse(path.contains("../"))
    }
    @Test fun unknownAuthorAndDateAreMarkedUnknownNotInvented(){
        val path=CosplayStoragePolicy.relativePath(entity(),gallery(null,null))
        assertTrue(path.contains("/Unknown creator/Undated_"))
        assertTrue(path.endsWith("/"))
    }
}
