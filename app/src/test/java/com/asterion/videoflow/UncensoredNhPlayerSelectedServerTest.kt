package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

/** Only selected first-party server assertions; no CAPTCHA or media requests. */
class UncensoredNhPlayerSelectedServerTest {
    private val iframe="https://nhplayer.com/v/SelectedId123456"
    private val server="https://nhplayer.com/player.php?i=abc&s=123&vid=SelectedId123456"

    @Test fun selectedBrowserChooserCanRecoverSinglePublishedServer(){
        val snapshot="<main><ul class='servers'>"+
            "<li data-id='$server'></li></ul></main>"
        assertEquals(server,SiteSpecificOriginalSources.selectedNhPlayerServer(
            iframe,snapshot))
    }

    @Test fun ambiguousAndUnrelatedServerCannotBeSubstituted(){
        val multiple="<ul class='servers'><li data-id='$server'></li>"+
            "<li data-id='https://nhplayer.com/player.php?i=other&s=123&vid=SelectedId123456'>"+
            "</li></ul>"
        assertNull(SiteSpecificOriginalSources.selectedNhPlayerServer(
            iframe,multiple))
        assertNull(SiteSpecificOriginalSources.selectedNhPlayerServer(
            iframe,"<ul class='servers'><li data-id='https://ads.example/player.php?"+
                "i=abc&s=123&vid=SelectedId123456'></li></ul>"))
        assertNull(SiteSpecificOriginalSources.selectedNhPlayerServer(
            "https://nhplayer.com/v/OtherId123456?ad=1",
            "<ul class='servers'><li data-id='$server'></li></ul>"))
    }
}
