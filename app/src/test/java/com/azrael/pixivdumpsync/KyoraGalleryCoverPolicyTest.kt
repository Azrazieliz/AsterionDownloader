package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class KyoraGalleryCoverPolicyTest {
    @Test fun signedOriginalImagesRemainDisplayable(){
        assertTrue(KyoraGalleryCoverPolicy.isImageUrl(
            "https://images.example.com/gallery/pic.jpg?token=abc"))
        assertTrue(KyoraGalleryCoverPolicy.isImageUrl(
            "https://cdn.example.com/photographs/001.webp"))
    }
    @Test fun archivesAndHtmlNeverBecomeFakeGalleryThumbnails(){
        for(link in listOf("https://m.4khd.com/45ycnl",
            "https://cdn.example.com/album.zip",
            "https://host.example.com/index.html",
            "https://server.example.com/movie.mp4",
            "https://server.example.com/pic.jpg.exe",
            "https://bad.example.com.evil.test/")) {
            assertFalse(link,KyoraGalleryCoverPolicy.isImageUrl(link))
        }
    }
}
