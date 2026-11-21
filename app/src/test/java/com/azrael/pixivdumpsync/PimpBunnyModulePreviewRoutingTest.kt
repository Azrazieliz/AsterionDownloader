package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

/** Models are photo previews in Cosplay, never video originals in Porn. */
class PimpBunnyModulePreviewRoutingTest {
    private val photos="https://pimpbunny.com/models/"
    private val videos="https://pimpbunny.com/"
    private val creator="https://pimpbunny.com/models/lolabunny.yu/"
    private val selected="https://pimpbunny.com/videos/selected-scene-123/"

    @Test fun modelPhotoCanAppearOnlyInCosplaySource(){
        assertTrue(KyoraCreatorPreviews.acceptedVideoCard(photos,creator))
        assertFalse(KyoraCreatorPreviews.acceptedVideoCard(videos,creator))
        assertFalse(KyoraCreatorPreviews.acceptedVideoCard(photos,selected))
        assertTrue(KyoraCreatorPreviews.acceptedVideoCard(videos,selected))
    }
    @Test fun categoryNavigationAndUnrelatedHostsCannotBecomePhotoCards(){
        for(url in listOf("https://pimpbunny.com/models/",
            "https://pimpbunny.com/categories/creators/",
            "https://pimpbunny.com/videos/12345/",
            "https://evil.example/models/lolabunny.yu/")){
            assertFalse(KyoraCreatorPreviews.acceptedVideoCard(photos,url))
        }
    }
    @Test fun explicitFirstPartyGalleryPhotoPostRemainsInCosplay(){
        assertTrue(KyoraCreatorPreviews.acceptedVideoCard(photos,
            "https://pimpbunny.com/photos/lolabunny.yu/post-1/"))
        assertFalse(KyoraCreatorPreviews.acceptedVideoCard(videos,
            "https://pimpbunny.com/photos/lolabunny.yu/post-1/"))
    }
}
