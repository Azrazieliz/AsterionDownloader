package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class SelectedRecordingNetworkOriginalTest {
    private val selected="https://pornavhd.com/2026/11/21/selected-full-recording/"
    private val player="https://video.playerrecord.cfd/e/Abc123Def456"
    private val media="https://z6v2p9a8.bkcdn.net/media/recording-original.mp4"
    @Test fun selectedBrowserRequestIsOnlyAProvenancedCandidateNotACompleteFile(){
        assertTrue(SelectedRecordingNetworkOriginal.isAttestedPlayer(
            selected,player))
        assertTrue(SelectedRecordingNetworkOriginal.approvedObservedRequest(
            selected,player,media,false,player))
        assertTrue(SelectedRecordingNetworkOriginal.approvedObservedRequest(
            selected,player,media,false,"https://video.playerrecord.cfd/"))
        // Exact selected WebView context is attested even if the browser's
        // referrer policy strips the cross-origin Referer header.
        assertTrue(SelectedRecordingNetworkOriginal.approvedObservedRequest(
            selected,player,media,false,null))
        assertTrue(UnverifiedSelectedPreviewGuard.shouldHold(
            selected,3000L,null))
    }
    @Test fun neverPromoteAdsBadHostsOtherPlayersOrUnselectedPages(){
        val bad=listOf(
            "https://ads.example/other.mp4",
            "https://z6v2p9a8.bkcdn.net.evil.example/media/original.mp4",
            "http://z6v2p9a8.bkcdn.net/media/original.mp4",
            "https://bkcdn.net/media/original.mp4",
            "https://z6v2p9a8.bkcdn.net/media/trailer.mp4",
            "https://z6v2p9a8.bkcdn.net/media/preview.mp4",
            "https://z6v2p9a8.bkcdn.net/media/original.m3u8",
            "https://z6v2p9a8.bkcdn.net/media/original.mp4?redirect=ads",
            "https://z6v2p9a8.bkcdn.net/media/original.mp4#fragment"
        )
        for(link in bad)assertFalse(link,
            SelectedRecordingNetworkOriginal.approvedObservedRequest(
                selected,player,link,false,player))
        assertFalse(SelectedRecordingNetworkOriginal.approvedObservedRequest(
            selected,player,media,true,player))
        // The caller already resolved the sole selected iframe from this
        // publisher's DOM. The request Referer must not silently switch to
        // a *different* iframe; URL shapes alone cannot prove article identity.
        assertFalse(SelectedRecordingNetworkOriginal.approvedObservedRequest(
            selected,"https://video.playerrecord.cfd/e/Other654321",
            media,false,player))
        assertFalse(SelectedRecordingNetworkOriginal.approvedObservedRequest(
            selected,player,media,false,"https://ads.example/"))
        assertFalse(SelectedRecordingNetworkOriginal.approvedObservedRequest(
            "https://pornavhd.com/",player,media,false,player))
        assertFalse(SelectedRecordingNetworkOriginal.approvedObservedRequest(
            "https://pornavhd.com/2026/11/21/selected-full-recording/",
            "https://video.playerrecord.cfd.evil.example/e/Abc123Def456",
            media,false,player))
    }
}
