package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class AsterionRecommendationsTest {
    @Test fun shortCjkCreatorNamesAreScoredInsteadOfDiscarded(){
        assertTrue(AsterionRecommendations.words("雪花").contains("雪花"))
        assertTrue(AsterionRecommendations.words("ゆき").contains("ゆき"))
        assertTrue(AsterionRecommendations.words("윤아").contains("윤아"))
        assertTrue(AsterionRecommendations.words("artist_007").contains("artist"))
        assertFalse(AsterionRecommendations.words("ab").contains("ab"))
    }
}
