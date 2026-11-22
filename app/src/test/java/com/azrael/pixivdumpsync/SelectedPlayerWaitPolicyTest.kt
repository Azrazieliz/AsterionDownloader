package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class SelectedPlayerWaitPolicyTest {
    @Test fun javideoInspectionScheduleFitsWithinBrowserDeadline(){
        val initial=SelectedPlayerWaitPolicy.inspectionDelayMs(true,0)
        val repeated=SelectedPlayerWaitPolicy.inspectionDelayMs(true,1)
        assertEquals(1_800L,initial)
        assertEquals(200L,repeated)
        val required=initial+SelectedPlayerWaitPolicy.maxPolls("javideo.net")*
            (repeated+1_400L)
        assertTrue("JAVIDEO retries must leave navigation time",required<40_000L)
        assertEquals(1_800L,SelectedPlayerWaitPolicy.inspectionDelayMs(false,8))
        assertEquals(200L,SelectedPlayerWaitPolicy.inspectionDelayMs(
            true,SelectedPlayerWaitPolicy.maxPolls("nhplayer.com")))
    }
    @Test fun javideoDefaultPlayerNeverEndsWaitBeforeSelectedStape(){
        val article="https://javideo.net/en/fc2ppv-4994603"
        fun compact(frame:String)=
            "<main><div class='video-player'><iframe src='$frame'>"+
                "</iframe></div></main>"
        assertFalse(SelectedPlayerWaitPolicy.selectedJavideoStapeReady(
            article,compact("https://play.javideo.net/embed/DefaultPlayer123")))
        assertTrue(SelectedPlayerWaitPolicy.selectedJavideoStapeReady(
            article,compact("https://streamtape.com/e/OriginalSelected123")))
        assertFalse(SelectedPlayerWaitPolicy.selectedJavideoStapeReady(
            article,compact("https://ads.example.org/e/OriginalSelected123")))
        assertFalse(SelectedPlayerWaitPolicy.selectedJavideoStapeReady(
            "https://javideo.net/en/",compact(
                "https://streamtape.com/e/OriginalSelected123")))
    }

    @Test fun signedPlaybackJSHasAdditionalBoundedTime(){
        assertEquals(11,SelectedPlayerWaitPolicy.maxPolls("3dhentaix.com"))
        assertTrue(SelectedPlayerWaitPolicy.maxPolls("3dhentaix.com")>
            SelectedPlayerWaitPolicy.maxPolls("rule34video.com"))
        assertTrue(SelectedPlayerWaitPolicy.maxPolls("3dhentaix.com")<=11)
    }
    @Test fun selectedNhPlayerDoesNotStopOnAnUnrelatedVideoOrEmptyAjax(){
        val iframe="https://nhplayer.com/v/Z2flAXXPucyCbVJ/"
        val server="https://nhplayer.com/player.php?i=selected&s=1&vid=chosen"
        val original="https://r2.1hanime.com/media/chosen.mp4?verify=short-lived-token"
        assertFalse(SelectedPlayerWaitPolicy.selectedNhPlayerReady(
            iframe,server,"<html><video src='https://ads.example/teaser.mp4'></video></html>"))
        assertFalse(SelectedPlayerWaitPolicy.selectedNhPlayerReady(
            iframe,server,"<html><video src=''></video></html>"))
        assertTrue(SelectedPlayerWaitPolicy.selectedNhPlayerReady(
            iframe,server,"<html><video src='"+original+"'></video></html>"))
        assertFalse(SelectedPlayerWaitPolicy.selectedNhPlayerReady(
            "https://nhplayer.com/v/not-related-id/",server,
            "<html><video src='"+original+"'></video></html>"))
        assertFalse(SelectedPlayerWaitPolicy.selectedNhPlayerReady(
            iframe,server,"<html><video src='https://ads.example/"+ 
            "chosen.mp4?verify=token'></video></html>"))
        assertEquals(18,SelectedPlayerWaitPolicy.maxPolls("nhplayer.com"))
        assertEquals(21,SelectedPlayerWaitPolicy.maxPolls("javideo.net"))
        assertEquals(15,SelectedPlayerWaitPolicy.maxPolls("play.javideo.net"))
    }
    @Test fun selectedRecordingIframesGetBoundedMediaRenderTime() {
        assertEquals(11,SelectedPlayerWaitPolicy.maxPolls("video.playerrecord.cfd"))
        assertEquals(11,SelectedPlayerWaitPolicy.maxPolls("vidara.to"))
        assertTrue(SelectedPlayerWaitPolicy.maxPolls("vidara.to") <= 11)
    }

    @Test fun selectedPornBuzPlayerGetsBoundedHlsInitializationBudget(){
        assertEquals(12,SelectedPlayerWaitPolicy.maxPolls(
            "player.watchstreamus.com"))
        assertEquals(12,SelectedPlayerWaitPolicy.maxPolls(
            "cdn.watchstreamus.com"))
        assertTrue(SelectedPlayerWaitPolicy.maxPolls(
            "player.watchstreamus.com") >
                SelectedPlayerWaitPolicy.maxPolls("rule34video.com"))
    }
    @Test fun workingRule34VideoKeepsItsExistingFastPath(){
        assertEquals(6,SelectedPlayerWaitPolicy.maxPolls("rule34video.com"))
        assertEquals(6,SelectedPlayerWaitPolicy.maxPolls("watchporn.to"))
    }
}
