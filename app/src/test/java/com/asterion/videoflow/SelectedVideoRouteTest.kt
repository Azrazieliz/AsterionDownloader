package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class SelectedVideoRouteTest {
    private val article="https://helloporn.com/video-74763-selected-post"
    private val frame="https://voe.sx/e/selectedOriginal123"
    @Test fun unconfiguredOrDirectChildInheritsPublishersProtectedRoute(){
        assertEquals(article,
            NetworkRoute.selectedRouteSite(article,frame,null))
        assertEquals(article,
            NetworkRoute.selectedRouteSite(article,frame,"direct"))
        assertEquals(article,
            NetworkRoute.selectedRouteSite(article,frame,"invalid"))
    }
    @Test fun explicitHostRouteIsUsedOnlyForSelectedChild(){
        for(mode in listOf("http","socks5","vpn")){
            assertEquals(frame,
                NetworkRoute.selectedRouteSite(article,frame,mode))
        }
        assertEquals(article,NetworkRoute.selectedRouteSite(
            article,article,"socks5"))
    }
    @Test fun allSiteSpecificPathsInheritSafelyOrSelectApprovedChild(){
        val guru="https://jav.guru/123456/mxgs-1452/"
        val cdn="https://s4.maxstream.org/selected/master.m3u8?signature=x"
        val javideo="https://javideo.net/en/fc2ppv-4994603"
        val selectedPlayer="https://play.javideo.net/embed/selectedplayer123"
        assertEquals(guru,NetworkRoute.selectedRouteSite(guru,cdn,"direct"))
        assertEquals(cdn,NetworkRoute.selectedRouteSite(guru,cdn,"socks5"))
        assertEquals(javideo,NetworkRoute.selectedRouteSite(
            javideo,selectedPlayer,null))
        assertEquals(selectedPlayer,NetworkRoute.selectedRouteSite(
            javideo,selectedPlayer,"http"))
    }
}
