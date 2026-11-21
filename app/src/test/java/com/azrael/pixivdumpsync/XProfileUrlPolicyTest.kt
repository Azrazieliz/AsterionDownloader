package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class XProfileUrlPolicyTest {
    @Test fun onlyOneCanonicalSubscriptionPerRealXProfile(){
        val inputs=listOf(
            "https://x.com/Mega_Artist",
            "https://www.twitter.com/mega_artist/",
            "https://mobile.twitter.com/MEGA_ARTIST",
            "https://fxtwitter.com/Mega_Artist"
        )
        val normalized=inputs.map{XProfileUrlPolicy.parse(it)}
        assertTrue(normalized.all{it!=null})
        assertEquals(setOf("https://x.com/mega_artist"),
            normalized.mapNotNull{it?.canonical}.toSet())
    }
    @Test fun noTweetFollowingOrSuggestedViewCanBeSavedAsAnOwnerFollow(){
        for(address in listOf(
            "https://x.com/owner/status/123456789",
            "https://x.com/owner/following",
            "https://x.com/owner/media",
            "https://x.com/owner/photo/1",
            "https://x.com/home",
            "https://x.com/following",
            "https://x.com/owner?screen=suggestions",
            "https://x.com/owner#posts",
            "https://x.com.evil.example/owner",
            "http://x.com/owner",
            "https://x.com:8443/owner"
        )){
            assertNull(address,XProfileUrlPolicy.parse(address))
        }
    }
}
