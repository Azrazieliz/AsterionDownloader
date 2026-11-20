package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

/** Partial Pixiv profile artwork previews must not be treated as complete just
 * because one card has a thumbnail. The existing signed-in Pixiv API refreshes
 * missing artwork URLs while retaining already valid artwork evidence. */
class ArtworkPreviewRecoveryTest {
    private fun work(id:String,thumbnail:String?)=PixivApi.ArtworkPreview(
        id,"103566470","Work $id",thumbnail)

    @Test fun oneValidPreviewDoesNotHideFourEmptyThumbnails(){
        val cached=listOf(work("1","https://i.pximg.net/img-master/1.jpg"),
            work("2",null),work("3",""),work("4",null),work("5",""))
        assertTrue(ArtworkPreviewCodec.needsThumbnailRecovery(cached))
        val encoded=ArtworkPreviewCodec.encode(cached)
        assertTrue(ArtworkPreviewCodec.needsThumbnailRecovery(
            ArtworkPreviewCodec.decode(encoded)))
    }

    @Test fun completeAndMissingPixivArtworksAreDistinguished(){
        assertTrue(ArtworkPreviewCodec.needsThumbnailRecovery(emptyList()))
        assertFalse(ArtworkPreviewCodec.needsThumbnailRecovery(listOf(
            work("1","https://i.pximg.net/img-master/1.jpg"),
            work("2","https://i.pximg.net/img-master/2.jpg"))))
    }
}
