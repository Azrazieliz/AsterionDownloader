package com.azrael.galleryflow

import org.junit.Assert.*
import org.junit.Test

class KyoraSelectedGalleryIdentityTest {
    @Test fun buonduaLegacyDomainRoutesToNativeAdapter() {
        val chosen=AdapterRegistry.forUrl("https://buondua.com/")
        assertEquals(SourceId.BUONDUA,chosen.source)
        val other=AdapterRegistry.forUrl("https://buondua.net/albums/rose-239")
        assertEquals(SourceId.BUONDUA,other.source)
    }

    @Test fun buonduaNativeIdIsActualAlbumIdNotGenericHash() {
        val selected="https://buondua.com/albums/rose-239"
        val exact=KyoraSelectedGalleryIdentity.canonicalUrl(
            SourceId.BUONDUA,selected)
        assertEquals("https://buondua.net/albums/rose-239",exact)
        assertEquals("rose-239",
            KyoraSelectedGalleryIdentity.galleryId(SourceId.BUONDUA,exact))
        assertTrue(KyoraSelectedGalleryIdentity.sameApprovedSite(
            "https://buondua.net/albums",exact))
    }

    @Test fun existingNativeAlbumIdsRemainCompatibleWithKyoraSync() {
        assertEquals("3456",
            KyoraSelectedGalleryIdentity.galleryId(SourceId.KIUTAKU,
                "https://kiutaku.com/3456"))
        assertEquals("3519/cosplay-set",
            KyoraSelectedGalleryIdentity.galleryId(SourceId.FOUR_K_HD,
                "https://www.4khd.com/content/3519/cosplay-set.html"))
        assertEquals("cosplay-title",
            KyoraSelectedGalleryIdentity.galleryId(SourceId.COSPLAYTELE,
                "https://cosplaytele.com/cosplay-title/"))
    }

    @Test fun unknownDomainAndNonAlbumsNeverUseBuonduaDownloader() {
        assertFalse(KyoraSelectedGalleryIdentity.sameApprovedSite(
            "https://buondua.net/albums",
            "https://buondua.net.bad.example/albums/rose-239"))
        assertFalse(KyoraSelectedGalleryIdentity.sameApprovedSite(
            "https://buondua.net/albums",
            "https://unrelated.test/albums/rose-239"))
        assertThrows(IllegalArgumentException::class.java) {
            KyoraSelectedGalleryIdentity.galleryId(
                SourceId.BUONDUA,"https://buondua.net/search")
        }
        assertThrows(IllegalArgumentException::class.java) {
            KyoraSelectedGalleryIdentity.canonicalUrl(
                SourceId.BUONDUA,"https://buondua.com.evil.test/albums/rose-239")
        }
    }
}
