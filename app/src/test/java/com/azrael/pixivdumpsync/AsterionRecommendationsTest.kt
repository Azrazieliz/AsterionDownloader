package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class AsterionRecommendationsTest {
    @Test fun shortCjkCreatorNamesAreScoredInsteadOfDiscarded(){
        assertTrue(CreatorNameTokens.words("雪花").contains("雪花"))
        assertTrue(CreatorNameTokens.words("ゆき").contains("ゆき"))
        assertTrue(CreatorNameTokens.words("윤아").contains("윤아"))
        assertTrue(CreatorNameTokens.words("artist_007").contains("artist"))
        assertFalse(CreatorNameTokens.words("ab").contains("ab"))
    }
}
