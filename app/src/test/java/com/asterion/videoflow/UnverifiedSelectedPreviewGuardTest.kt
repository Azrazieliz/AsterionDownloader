package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class UnverifiedSelectedPreviewGuardTest {
    @Test fun twentySecondPreviewDoesNotBecomeFullOriginal() {
        val noReference=20_020L
        for(host in listOf("taiav.com","javideo.net",
            "livecamrips.to","uncensoredhentai.xxx","avple.tv",
            "pimpbunny.com","en.xchina.co","bubblecum.lol",
            "pornavhd.com","camstreams.tv","pornbuz.com")){
            assertTrue(host,UnverifiedSelectedPreviewGuard.shouldHold(
                "https://"+host+"/video/selected",noReference,null))
        }
    }
    @Test fun pornbuzPreviewCannotSelfAttestItsTwentySecondRuntime() {
        for(reference in listOf(null,20L,21L,1800L)){
            assertTrue("Publisher-selected teaser cannot certify itself",
                UnverifiedSelectedPreviewGuard.shouldHold(
                    "https://pornbuz.com/video/some-selected-scene-829383/",
                    20_010L,reference))
        }
        assertFalse(UnverifiedSelectedPreviewGuard.shouldHold(
            "https://pornbuz.com/video/some-selected-scene-829383/",
            120_000L,null))
    }

    @Test fun acceptedOriginalReferenceOverridesShortClipSuspicion() {
        assertFalse(UnverifiedSelectedPreviewGuard.shouldHold(
            "https://camstreams.tv/videos/1/short/",20_020L,20L))
        assertFalse(UnverifiedSelectedPreviewGuard.shouldHold(
            "https://taiav.com/cn/movie/1",60_000L,null))
    }
    @Test fun allFrozenWorkingPublishersAreUnaffected() {
        for(host in listOf("jav.guru","www.pornhub.com",
            "fullporner.com","rule34video.com","eporner.com",
            "freepornvideos.xxx","hentaibros.net")){
            assertFalse(host,UnverifiedSelectedPreviewGuard.shouldHold(
                "https://"+host+"/video/selected",20_020L,null))
        }
    }
}
