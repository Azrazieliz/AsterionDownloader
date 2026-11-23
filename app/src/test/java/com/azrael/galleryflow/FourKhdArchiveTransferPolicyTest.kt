package com.azrael.galleryflow

import org.junit.Assert.*
import org.junit.Test

/** Covers only 4KHD's official selected bridge; no live network or file writes. */
class FourKhdArchiveTransferPolicyTest {
    private val selected="https://www.4khd.com/content/14/yeha-yeha-pure2.html"
    private fun media(url:String,provider:String?,kind:MediaKind=MediaKind.ARCHIVE)=
        MediaRef(SourceId.FOUR_K_HD,"selected-archive","album",0,
            url,url,selected,"application/zip",kind,provider)

    @Test fun official4khdTeraBoxBridgeUsesShareHostRefererForOriginal(){
        val archive=media("https://m.4khd.com/CIY30g","4KHD")
        assertEquals("https://www.terabox.com/",
            GalleryArchiveRefererPolicy.preferred(archive))
        assertEquals("https://www.terabox.com/",
            GalleryArchiveRefererPolicy.preferred(
                media("https://m.4khd.com/CIY30g","terabox")))
    }

    @Test fun ordinaryGalleryImagesAndNative4khdArchivesPreservePublisher(){
        assertEquals(selected,GalleryArchiveRefererPolicy.preferred(
            media("https://m.4khd.com/CIY30g","4KHD",MediaKind.IMAGE)))
        assertEquals(selected,GalleryArchiveRefererPolicy.preferred(
            media("https://cdn.4khd.com/archives/real.zip","4KHD")))
        assertEquals(selected,GalleryArchiveRefererPolicy.preferred(
            media("https://m.4khd.com.evil.example/CIY30g","4KHD")))
    }

    @Test fun unrelatedProvidersKeepOriginalRefererRules(){
        assertEquals(selected,GalleryArchiveRefererPolicy.preferred(
            media("https://cosplaytele.com/media/real.zip","CosplayTele")))
        assertEquals("https://gofile.io/d/real",
            GalleryArchiveRefererPolicy.preferred(
                media("https://gofile.io/d/real","Gofile")))
    }
}
