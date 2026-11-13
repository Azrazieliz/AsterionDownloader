package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class PublisherPreviewFallbackTest {
    @Test fun javGuruVerifiedPublisherImageUsedBeforeBlockedCdn(){
        val cdn="https://cdn.javmiku.com/wp-content/uploads/2026/11/dvmm461pl-550x374.jpg"
        assertEquals(listOf(
            "https://jav.guru/wp-content/uploads/2026/11/dvmm461pl-550x374.jpg",
            cdn),PublisherPreviewFallback.candidates(cdn,
                "https://jav.guru/1076811/a-real-publisher-title/"))
    }
    @Test fun javGuruVerifiedRelatedThumbnailUsesPublisherThumbsPath(){
        val cdn="https://cdn.javnorth.com/wp-content/uploads/thumbs/2026/11/example.jpg?ref=tracking"
        assertEquals(listOf(
            "https://jav.guru/wp-content/uploads/thumbs/2026/11/example.jpg",cdn),
            PublisherPreviewFallback.candidates(cdn,
                "https://jav.guru/1076811/a-real-publisher-title/"))
    }
    @Test fun unrelatedCdnCannotBePromotedToPublisher(){
        val unknown="https://cdn.other-site.test/wp-content/uploads/thumbs/2026/11/photo.jpg"
        assertEquals(listOf(unknown),PublisherPreviewFallback.candidates(
            unknown,"https://jav.guru/1076811/a-real-publisher-title/"))
    }
    @Test fun doesNotRewriteOtherProvidersOrUnrelatedPaths(){
        val src="https://cdn.javmiku.com/wp-content/uploads/2026/11/poster.jpg"
        assertEquals(listOf(src),PublisherPreviewFallback.candidates(src,
            "https://3dhentaix.com/a-post/"))
        assertEquals(listOf("https://cdn.javmiku.com/assets/test.jpg"),
            PublisherPreviewFallback.candidates(
                "https://cdn.javmiku.com/assets/test.jpg","https://jav.guru/"))
    }
}
