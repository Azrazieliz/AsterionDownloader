package com.azrael.galleryflow

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectGalleryParserTest {
    @Test
    fun cosplayTeleMixedAlbumRetainsImagesAndArchiveOnlyVideos() {
        val article="https://cosplaytele.com/approved-album/"
        val direct=listOf(DirectPageAsset(
            "https://cosplaytele.com/files/original.jpg",article,
            MediaKind.IMAGE,"image/jpeg"))
        val archive="https://gofile.io/d/verified" to "Gofile"
        val planned=planCosplayTeleAlbumMedia(
            "approved-album",article,direct,archive,null,3)
        assertEquals(2,planned.size)
        assertEquals(MediaKind.IMAGE,planned[0].kind)
        assertEquals(MediaKind.ARCHIVE,planned[1].kind)
        assertTrue(planned[1].archiveVideosOnly)
        assertEquals("Gofile",planned[1].provider)
        assertEquals(article,planned[1].referer)
        assertEquals(1,planned[1].index)
        // A published direct video is not silently replaced by a duplicate archive.
        val video=DirectPageAsset(
            "https://cosplaytele.com/files/original.mp4",article,
            MediaKind.VIDEO,"video/mp4")
        val alreadyCovered=planCosplayTeleAlbumMedia(
            "approved-album",article,direct+video,archive,null,1)
        assertEquals(2,alreadyCovered.size)
        assertTrue(alreadyCovered.none{it.kind==MediaKind.ARCHIVE})
        val imagesOnly=planCosplayTeleAlbumMedia(
            "approved-album",article,direct,archive,null,0)
        assertEquals(1,imagesOnly.size)
        assertEquals(MediaKind.IMAGE,imagesOnly.single().kind)
        val archiveOnly=planCosplayTeleAlbumMedia(
            "approved-album",article,emptyList(),archive,null,2)
        assertEquals(1,archiveOnly.size)
        assertFalse(archiveOnly.single().archiveVideosOnly)
    }

    @Test
    fun extractsLinkedImagesLazyImagesAndVideosButNotProviderLinks() {
        val html = """
            <html><body>
              <main>
                <a href="https://cdn.example/gallery/full-001.jpg">
                  <img src="https://cdn.example/gallery/thumb-001.jpg" width="900" height="1300">
                </a>
                <img data-original="https://cdn.example/gallery/full-002.webp" width="900" height="1300">
                <video controls>
                  <source src="https://cdn.example/gallery/clip-003.mp4" type="video/mp4">
                </video>
                <a href="https://www.terabox.com/s/example">Download ZIP</a>
                <img src="https://cdn.example/assets/logo.png" width="80" height="80">
              </main>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://www.4khd.com/content/test.html")
        val media = directPageAssets(doc, "https://www.4khd.com/content/test.html")

        assertTrue(media.any { it.url.endsWith("full-001.jpg") && it.kind == MediaKind.IMAGE })
        assertTrue(media.any { it.url.endsWith("full-002.webp") && it.kind == MediaKind.IMAGE })
        assertTrue(media.any { it.url.endsWith("clip-003.mp4") && it.kind == MediaKind.VIDEO })
        assertTrue(media.none { it.url.contains("terabox") })
        assertTrue(media.none { it.url.contains("logo.png") })
    }

    @Test
    fun prefersLargestSrcsetCandidate() {
        val html = """
            <html><body><article>
              <img src="https://cdn.example/small.jpg"
                   srcset="https://cdn.example/small.jpg 400w, https://cdn.example/large.jpg 1600w"
                   width="800" height="1200">
            </article></body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://cosplaytele.com/example/")
        val media = directPageAssets(doc, "https://cosplaytele.com/example/")

        assertTrue(media.any { it.url.endsWith("large.jpg") })
    }
}
