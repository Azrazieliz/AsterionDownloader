package com.asterion.videoflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VideoCandidatePlanTest {
    @Test fun publisherPageIsRefererOnlyForItsAlternatePlayers(){
        assertEquals(null,VideoCandidatePlan.referer(
            "https://example.org/watch/a","https://example.org/watch/a"))
        assertEquals("https://example.org/watch/a",VideoCandidatePlan.referer(
            "https://example.org/watch/a","https://cdn.example.org/e/abc"))
    }
    @Test fun workingVerifiedExitCanDiscoverPublisherOwnedHtml5Player(){
        assertTrue(VideoCandidatePlan.mayInspectPublisher(true,false,true))
        assertTrue(VideoCandidatePlan.mayInspectPublisher(false,false,false))
        assertFalse(VideoCandidatePlan.mayInspectPublisher(true,false,false))
        assertFalse(VideoCandidatePlan.mayInspectPublisher(false,true,true))
    }
    @Test fun selectedPageIsRetainedWhenNoIframeWasDiscovered(){
        assertEquals(listOf("https://example.org/watch/a"),
            VideoCandidatePlan.ordered("https://example.org/watch/a",
                emptyList(),emptyList()))
    }
    @Test fun publisherOwnedEpornerPublicEmbedPrecedesBrokenWatchPage(){
        val selected="https://www.eporner.com/video-AcCjSXiLGwV/example-title/"
        val expected="https://www.eporner.com/embed/AcCjSXiLGwV/"
        assertEquals(listOf(expected,selected),VideoCandidatePlan.ordered(
            selected,EpornerOriginalSource.videoCandidates(selected,false),
            listOf(expected)))
    }
    @Test fun selectedPageIsAlwaysFirstAndAlternativesAreDistinct(){
        assertEquals(listOf("https://example.org/watch/a",
                "https://example.org/embed/a",
                "https://media.example.org/original.mp4"),
            VideoCandidatePlan.ordered("https://example.org/watch/a",
                listOf("https://example.org/embed/a","https://example.org/watch/a"),
                listOf("https://example.org/embed/a",
                    "https://media.example.org/original.mp4")))
    }
}
