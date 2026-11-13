package com.azrael.galleryflow

import org.junit.Assert.*
import org.junit.Test
import org.jsoup.Jsoup

class SelectedAlbumBoundaryTest {
    @Test fun relatedSidebarPhotoDoesNotExpandTheSelectedAlbum(){
        val base="https://cosplay.example.org/albums/chosen"
        val doc=Jsoup.parse("""
          <main class="album">
            <a class="photo" href="/photo/original1"><img src="/thumb/original1.jpg"></a>
          </main>
          <aside class="related"><a href="/photo/other">
            <img src="/thumb/other.jpg"></a></aside>
        """.trimIndent(),base)
        assertEquals(listOf("https://cosplay.example.org/photo/original1"),
            AlbumImageExtractor.photoPages(doc,base))
    }
    @Test fun pageImagesExcludeUnrelatedRecommendations(){
        val base="https://cosplay.example.org/albums/chosen"
        val doc=Jsoup.parse("""
          <div class="album"><img src="/cdn/original1.jpg" width="1920"></div>
          <div class="recommendations"><img src="/cdn/other.jpg" width="1920"></div>
        """.trimIndent(),base)
        val found=AlbumImageExtractor.directImages(doc).map{it.url}
        assertEquals(listOf("https://cosplay.example.org/cdn/original1.jpg"),found)
    }
}
