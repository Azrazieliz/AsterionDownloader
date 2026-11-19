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
    @Test fun partialOrUnknownFollowingCannotDeleteExistingSubscriptions(){
        assertFalse(XFollowingImportPolicy.canReconcile(true,20,513))
        assertFalse(XFollowingImportPolicy.canReconcile(true,513,null))
        assertFalse(XFollowingImportPolicy.canReconcile(false,513,513))
        assertFalse(XFollowingImportPolicy.canReconcile(true,514,513))
        assertFalse(XFollowingImportPolicy.canReconcile(true,0,0))
        assertTrue(XFollowingImportPolicy.canReconcile(true,513,513))
    }
    @Test fun followingPageMustAlsoBelongToAuthenticatedXProfile(){
        assertTrue(XFollowingImportPolicy.isAuthenticatedOwnFollowingPage(
            "/owner_42/following","owner_42","/owner_42"))
        assertTrue(XFollowingImportPolicy.isAuthenticatedOwnFollowingPage(
            "/Owner_42/following","owner_42","/OWNER_42/"))
        assertFalse(XFollowingImportPolicy.isAuthenticatedOwnFollowingPage(
            "/other_user/following","owner_42","/owner_42"))
        assertFalse(XFollowingImportPolicy.isAuthenticatedOwnFollowingPage(
            "/other_user/following","other_user","/owner_42"))
        assertFalse(XFollowingImportPolicy.isAuthenticatedOwnFollowingPage(
            "/owner_42/following","owner_42","/someone_else"))
        assertFalse(XFollowingImportPolicy.isAuthenticatedOwnFollowingPage(
            "/owner_42/following","owner_42",""))
        assertFalse(XFollowingImportPolicy.isAuthenticatedOwnFollowingPage(
            "/owner_42/following","owner_42","https://x.com/owner_42"))
        assertFalse(XFollowingImportPolicy.isAuthenticatedOwnFollowingPage(
            "/home","owner_42","/owner_42"))
    }

}
