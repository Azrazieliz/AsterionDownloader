package com.azrael.pixivdumpsync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAccountPolicyTest {
    @Test fun onlyExplicitAccountRequirementTriggersConnection(){
        for(error in listOf("Novel source HTTP 401",
            "Authenticated site session required",
            "login required","account required","Please sign in")){
            assertTrue(error,NovelAccountPolicy.needsBrowserConnection(error))
        }
    }
    @Test fun public403AndMissingLinkNeverFabricateLogin(){
        for(error in listOf("Provider HTTP 403",
            "Access denied by edge protection",
            "interactive browser verification",
            "No original download link found",
            "Connection timed out","HTTP 503","HTTP 429",
            "Missing 4 numbered chapters; refusing incomplete EPUB",
            "Novel index pagination did not finish")){
            assertFalse(error,NovelAccountPolicy.needsBrowserConnection(error))
        }
    }
}
