package com.asterion.videoflow

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoCandidatePlanTest {
    @Test fun selectedPageIsRetainedWhenNoIframeWasDiscovered(){
        assertEquals(listOf("https://example.org/watch/a"),
            VideoCandidatePlan.ordered("https://example.org/watch/a",
                emptyList(),emptyList()))
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
