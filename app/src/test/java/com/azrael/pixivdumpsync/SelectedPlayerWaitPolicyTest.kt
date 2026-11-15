package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class SelectedPlayerWaitPolicyTest {
    @Test fun signedPlaybackJSHasAdditionalBoundedTime(){
        assertEquals(11,SelectedPlayerWaitPolicy.maxPolls("3dhentaix.com"))
        assertTrue(SelectedPlayerWaitPolicy.maxPolls("3dhentaix.com")>
            SelectedPlayerWaitPolicy.maxPolls("rule34video.com"))
        assertTrue(SelectedPlayerWaitPolicy.maxPolls("3dhentaix.com")<=11)
    }
    @Test fun workingRule34VideoKeepsItsExistingFastPath(){
        assertEquals(6,SelectedPlayerWaitPolicy.maxPolls("rule34video.com"))
        assertEquals(6,SelectedPlayerWaitPolicy.maxPolls("watchporn.to"))
    }
}
