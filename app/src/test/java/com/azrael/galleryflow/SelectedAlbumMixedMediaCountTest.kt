package com.azrael.galleryflow

import org.junit.Assert.*
import org.junit.Test

class SelectedAlbumMixedMediaCountTest {
    private fun media(id:String,kind:MediaKind,expectedArchive:Int?=null)=
        MediaRef(SourceId.COSPLAYTELE,id,"gallery-1",0,
            "https://cdn.cosplaytele.com/$id","https://cdn.cosplaytele.com/$id",
            "https://cosplaytele.com/selected/",kind=kind,
            expectedArchivePhotos=expectedArchive)

    @Test fun declaredPhotoCountDoesNotIncludeRealVideosOrVideoOnlyArchive(){
        val items=listOf(
            media("one.jpg",MediaKind.IMAGE),
            media("two.jpg",MediaKind.IMAGE),
            media("movie.mp4",MediaKind.VIDEO),
            media("videos.zip",MediaKind.ARCHIVE)
        )
        assertTrue(SelectedAlbumScope.verifySelectedPhotos(2,items))
        assertThrows(AdapterException::class.java){
            SelectedAlbumScope.verifySelectedPhotos(3,items)
        }
    }

    @Test fun authoritativeFullAlbumArchiveKeepsItsIndependentPhotoProof(){
        val archive=media("album.zip",MediaKind.ARCHIVE,37)
        assertFalse(SelectedAlbumScope.verifySelectedPhotos(37,listOf(archive)))
    }

    @Test fun missingPhotoStillFailsClosed(){
        assertThrows(AdapterException::class.java){
            SelectedAlbumScope.verifySelectedPhotos(37,listOf(
                media("one.jpg",MediaKind.IMAGE),
                media("film.mp4",MediaKind.VIDEO)))
        }
    }
}
