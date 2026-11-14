package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class AsterionPeerTrustTest {
    @Test fun acceptsExistingAsterionApplicationPackages(){
        assertTrue(AsterionPeerTrust.allowedPackage("com.asterion.art"))
        assertTrue(AsterionPeerTrust.allowedPackage("com.ailm.android"))
        assertTrue(AsterionPeerTrust.allowedPackage("com.gamepilot"))
        assertTrue(AsterionPeerTrust.allowedPackage("com.azrael.asterion.core"))
    }
    @Test fun neverTrustsAnUnrelatedCallerByPackageName(){
        assertFalse(AsterionPeerTrust.allowedPackage("com.random.downloader"))
        assertFalse(AsterionPeerTrust.allowedPackage("com.asterion"))
        assertFalse(AsterionPeerTrust.allowedPackage("com.gamepilot.fake"))
    }
}
