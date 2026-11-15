package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class XFollowingImportPolicyTest {
    @Test fun neverImportSuggestedOrVisibleUnfollowedProfiles(){
        assertFalse(XFollowingImportPolicy.isFollowedAccount("BCgame",false))
        assertFalse(XFollowingImportPolicy.isFollowedAccount("SuggestedUser",false))
        assertFalse(XFollowingImportPolicy.isFollowedAccount("home",true))
        assertFalse(XFollowingImportPolicy.isFollowedAccount("very-long-user-handle",true))
    }
    @Test fun onlyVerifiedActualFollowingFromOwnList(){
        assertTrue(XFollowingImportPolicy.isFollowedAccount("hanamisan_yobi",true))
        assertTrue(XFollowingImportPolicy.isOwnFollowingPage("/owner_42/following","owner_42"))
        assertFalse(XFollowingImportPolicy.isOwnFollowingPage("/bcgame/following","owner_42"))
        assertFalse(XFollowingImportPolicy.isOwnFollowingPage("/home","owner_42"))
        assertFalse(XFollowingImportPolicy.isOwnFollowingPage("/owner_42/followers","owner_42"))
        assertFalse(XFollowingImportPolicy.isOwnFollowingPage("/owner_42/following",""))
    }
}
