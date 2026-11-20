package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class IdentityAndGallerySearchRegressionTest {
    @Test fun unrelatedNamesCannotHijackModelSearch() {
        assertFalse(SearchExactness.matchesPhrase(
            "Bella Alice","Barbara Bella and Alice Marshall"))
        assertTrue(SearchExactness.matchesPhrase(
            "Bella Alice","Bella Alice - selected official recording"))
        assertTrue(SearchExactness.matchesPhrase(
            "Tina Kitsune","Official Tina Kitsune feature"))
        assertFalse(SearchExactness.matchesPhrase(
            "Tina Kitsune","Tina with someone named Kitsune"))
    }

    @Test fun photoGalleryTorrentsRemainDistinctFromVideos() {
        assertTrue(TorrentGalleryClassifier.likelyGallery(
            "Cosplay model image gallery 2026"))
        assertTrue(TorrentGalleryClassifier.likelyGallery(
            "Artist photoset collection"))
        assertFalse(TorrentGalleryClassifier.likelyGallery(
            "Video release 1080p WEB DL"))
        assertFalse(TorrentGalleryClassifier.likelyGallery(
            "Unknown data release"))
    }
}
