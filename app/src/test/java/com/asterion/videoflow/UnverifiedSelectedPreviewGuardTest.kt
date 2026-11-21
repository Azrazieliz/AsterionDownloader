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
