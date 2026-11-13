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
    @Test fun doesNotRewriteOtherProvidersOrUnrelatedPaths(){
        val src="https://cdn.javmiku.com/wp-content/uploads/2026/11/poster.jpg"
        assertEquals(listOf(src),PublisherPreviewFallback.candidates(src,
            "https://3dhentaix.com/a-post/"))
        assertEquals(listOf("https://cdn.javmiku.com/assets/test.jpg"),
            PublisherPreviewFallback.candidates(
                "https://cdn.javmiku.com/assets/test.jpg","https://jav.guru/"))
    }
}
