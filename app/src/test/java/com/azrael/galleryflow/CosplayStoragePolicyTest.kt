package com.azrael.galleryflow

import org.junit.Assert.*
import org.junit.Test

class CosplayStoragePolicyTest {
    @Test fun allAlbumsShareOneFlatDirectory(){
        val path=CosplayStoragePolicy.relativePath("Kyora")
        assertEquals("Download/Kyora/",path)
        assertFalse(path.contains("Cosplayer"))
        assertFalse(path.contains("2026-"))
    }
    @Test fun stableOriginalFileNamesDistinguishPagesAndAlbums(){
        val x=MediaRef(SourceId.GENERIC,"page1","albumA",0,
            "https://image.example/image1.jpg","https://image.example/image1.jpg","https://example.org")
        val y=x.copy(galleryStableId="albumB")
        val next=x.copy(index=1)
        assertNotEquals(FlatStoragePolicy.filename(x,"jpg"),
            FlatStoragePolicy.filename(y,"jpg"))
        assertNotEquals(FlatStoragePolicy.filename(x,"jpg"),
            FlatStoragePolicy.filename(next,"jpg"))
    }
}
