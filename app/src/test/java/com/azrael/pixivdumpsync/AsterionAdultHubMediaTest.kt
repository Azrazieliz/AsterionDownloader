package com.azrael.pixivdumpsync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsterionAdultHubMediaTest {
    private fun entry(url:String,area:String="HENTAI")=IndexedItem(
        url,"Actual Episode", "https://hentaibros.net/img/post.webp",
        "https://hentaibros.net/",area,"video",1L,false)

    @Test fun firstPartySingleSlugHentaiIsNotDiscardedFromMediaLibrary(){
        assertTrue(AsterionAdultHub.realMedia(entry(
            "https://hentaibros.net/muramata-san-no-aijou-episode-2/")))
    }
    @Test fun navigationAndForeignHostDoNotBecomeFirstPartyMedia(){
        assertFalse(AsterionAdultHub.realMedia(entry(
            "https://hentaibros.net/latest-videos/")))
        assertFalse(AsterionAdultHub.realMedia(entry(
            "https://foreign.example/video/actual-episode/")))
    }
}
