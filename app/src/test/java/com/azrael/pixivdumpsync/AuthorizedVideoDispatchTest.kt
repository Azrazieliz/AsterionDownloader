package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class AuthorizedVideoDispatchTest {
    private fun item(url:String,kind:String="video",area:String="ADULT")=
        IndexedItem(url,"Selected video",null,"https://rule34video.com/",
            area,kind,0L,false)

    @Test fun selectedWatchPageGoesToVideoExtractorNotImageResolver(){
        assertTrue(AuthenticatedItemDownloader.usesVideoEngine(
            item("https://rule34video.com/video/4727221/")))
        assertTrue(AuthenticatedItemDownloader.usesVideoEngine(
            item("https://3dhentaix.com/ntr-compilation/")))
    }
    @Test fun homepagesAndImagesNeverBecomeVideoDownloads(){
        assertFalse(AuthenticatedItemDownloader.usesVideoEngine(
            item("https://rule34video.com/")))
        // Index errors must not send genuine selected player pages through
        // the image/archive resolver ("No verified original found").
        assertTrue(AuthenticatedItemDownloader.usesVideoEngine(
            item("https://rule34video.com/video/4727221/","image")))
        assertFalse(AuthenticatedItemDownloader.usesVideoEngine(
            item("https://rule34video.com/","video")))
        assertFalse(AuthenticatedItemDownloader.usesVideoEngine(
            item("https://rule34video.com/video/4727221/","video","COMICS")))
    }
}
