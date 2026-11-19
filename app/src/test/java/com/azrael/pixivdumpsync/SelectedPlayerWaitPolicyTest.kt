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
        assertEquals(9,SelectedPlayerWaitPolicy.maxPolls("nhplayer.com"))
        assertEquals(11,SelectedPlayerWaitPolicy.maxPolls("javideo.net"))
        assertEquals(11,SelectedPlayerWaitPolicy.maxPolls("play.javideo.net"))
    }
    @Test fun workingRule34VideoKeepsItsExistingFastPath(){
        assertEquals(6,SelectedPlayerWaitPolicy.maxPolls("rule34video.com"))
        assertEquals(6,SelectedPlayerWaitPolicy.maxPolls("watchporn.to"))
    }
}
