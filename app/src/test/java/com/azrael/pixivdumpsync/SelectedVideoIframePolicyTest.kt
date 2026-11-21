package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class SelectedVideoIframePolicyTest {
    @Test fun exactSelectedPublisherFramesAreNotBlockedOnAndroid() {
        val cases=listOf(
            "https://archivebate.com/watch/16458050" to
                "https://mixdrop.ag/e/abC123de",
            "https://archivebate.com/u/watch/16458050" to
                "https://mixdrop.ag/e/abC123de",
            "https://javideo.net/en/fc2ppv-4994603" to
                "https://streamtape.com/e/Ab1cd2ef3G",
            "https://javideo.net/en/fc2ppv-4994603" to
                "https://play.javideo.net/player/AbC123456",
            "https://uncensoredhentai.xxx/watch/so_low-futago-shimai-to-katei-kyoshi-episode-3/" to
                "https://nhplayer.com/v/Z2flAXXPucyCbVJ/",
            "https://roshy.tv/video/exact-scene/" to
                "https://kamehaus.net/e/abcdefGHI1",
            "https://livecamrips.to/video/11248256" to
                "https://vidara.to/KJq6mx17bB2/"
        )
        for((source,frame) in cases){
            assertTrue("selected iframe blocked: "+frame,
                SelectedVideoIframePolicy.allow(source,frame,false))
            assertFalse("external top-level escape admitted: "+frame,
                SelectedVideoIframePolicy.allow(source,frame,true))
        }
    }
    @Test fun advertisementsHostLookalikesAndUnrelatedFramesAreRejected() {
        val source="https://helloporn.com/video-115854-something"
        for(frame in listOf(
            "https://voe.sx.evil.test/e/wx8walm5hqkj",
            "https://voe.sx/video/recommendations",
            "https://ads.example.com/e/wx8walm5hqkj",
            "http://voe.sx/e/wx8walm5hqkj",
            "https://user@voe.sx/e/wx8walm5hqkj",
            "https://voe.sx/e/wx8walm5hqkj#fragment",
            "javascript:alert(1)"
        ))assertFalse(frame,SelectedVideoIframePolicy.allow(source,frame,false))
        assertFalse(SelectedVideoIframePolicy.allow(
            "https://javideo.net/en/fc2ppv-4994603",
            "https://play.javideo.net.evil.example/player/ABCDEF",false))
        assertTrue(SelectedVideoIframePolicy.allow(source,
            "https://helloporn.com/video-115854-something",true))
        assertTrue(SelectedVideoIframePolicy.allow(source,"about:blank",false))
        assertFalse(SelectedVideoIframePolicy.allow(source,
            "https://voe.sx/e/wx8walm5hqkj",false))
    }
    @Test fun selectedPlayersCannotBeReusedForUnrelatedPublishers() {
        val random="https://hentaihaven.xxx/watch/renseijutsushi-collet-episode-1/"
        assertFalse(SelectedVideoIframePolicy.allow(random,
            "https://voe.sx/e/wx8walm5hqkj",false))
        assertFalse(SelectedVideoIframePolicy.allow(random,
            "https://mixdrop.ag/e/abC123de",false))
    }
}
