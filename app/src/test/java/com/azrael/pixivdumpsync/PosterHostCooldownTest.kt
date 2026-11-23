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

    @Test fun transientDnsOutagePausesOnlyAffectedPosterHost(){
        val first="https://cdn-dns-a.example/images/poster1.jpg"
        val second="https://cdn-dns-a.example/images/poster2.jpg"
        val unrelated="https://cdn-dns-b.example/images/poster.jpg"
        val time=50_000L
        assertTrue(PosterHostCooldown.record(first,
            java.net.UnknownHostException("DNS resolver temporarily unavailable"),time))
        assertFalse(PosterHostCooldown.allowed(second,time+1))
        assertTrue(PosterHostCooldown.allowed(unrelated,time+1))
        assertFalse(PosterHostCooldown.allowed(second,time+11_999))
        assertTrue(PosterHostCooldown.allowed(second,time+12_001))
    }

    @Test fun nestedDnsFailureBacksOffButOtherIoErrorsStillRetry(){
        val first="https://cdn-nested-dns.example/a.webp"
        val second="https://cdn-nested-dns.example/b.webp"
        val time=85_000L
        val nested=java.io.IOException("Poster lookup",java.net.UnknownHostException("offline"))
        assertTrue(PosterHostCooldown.record(first,nested,time))
        assertFalse(PosterHostCooldown.allowed(second,time+1))
        val unrelated="https://unrelated-timeout.example/other.webp"
        assertFalse(PosterHostCooldown.record(unrelated,
            java.net.SocketTimeoutException("slow"),time))
        assertTrue(PosterHostCooldown.allowed(unrelated,time+1))
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
