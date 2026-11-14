package com.azrael.galleryflow

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectedAlbumRoutingTest {
    @Test fun exactAlbumDoesNotEnumeratePageRecommendations(){
        val url="https://cosplay.example.org/posts/collection-77"
        val selected=SourceEntity(SourceId.GENERIC,"url:77","Selected collection",
            url,"selected_album")
        val galleries=GenericSiteAdapter(HttpClient())
            .enumerateGalleries(selected).toList()
        assertEquals(1,galleries.size)
        assertEquals(url,galleries.single().canonicalUrl)
        assertEquals("Selected collection",galleries.single().title)
    }
}
