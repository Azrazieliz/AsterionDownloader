package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class PosterHostCooldownTest {
    @Test fun manySelectedPostersShareOneShortFailureCooldown() {
        val first="https://posters-502-test.example/video/1"
        val second="https://posters-502-test.example/video/2"
        val independent="https://unrelated-poster-test.example/video/3"
        assertTrue(PosterHostCooldown.allowed(first,1_000L))
        assertTrue(PosterHostCooldown.record(first,
            IllegalStateException("Selected Rule34Video poster page HTTP 502"),
            1_000L))
        assertFalse(PosterHostCooldown.allowed(second,1_001L))
        assertTrue(PosterHostCooldown.allowed(independent,1_001L))
        assertTrue(PosterHostCooldown.allowed(second,21_001L))
    }

    @Test fun authAndNetworkFailuresDoNotDisableOtherPublisherPosters() {
        val page="https://poster-auth-test.example/video/1"
        assertFalse(PosterHostCooldown.record(page,
            IllegalStateException("HTTP 403"),1_000L))
        assertFalse(PosterHostCooldown.record(page,
            java.net.SocketTimeoutException("timed out"),1_000L))
        assertTrue(PosterHostCooldown.allowed(page,1_002L))
        assertTrue(PosterHostCooldown.record(page,
            java.io.IOException("poster_http_503"),1_000L))
        assertFalse(PosterHostCooldown.allowed(page,1_002L))
    }
}
