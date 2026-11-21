package com.azrael.galleryflow

import org.junit.Assert.*
import org.junit.Test

/** The official archive link is delayed by 4KHD's own scripts.
 * Placeholder buttons must never be mistaken for an original download.
 */
class FourKhdBridgeUrlPolicyTest {
    private val client=HttpClient()

    @Test fun realTeraboxShareAndFileLinksAreEligible(){
        assertTrue(client.isReal4khdBridge(
            "https://www.terabox.com/s/1shareCode123"))
        assertTrue(client.isReal4khdBridge(
            "https://www.1024tera.com/wap/share/filelist?surl=1234abc"))
        assertTrue(client.isReal4khdBridge(
            "https://cdn.4khd.com/archives/selected-album.zip"))
    }

    @Test fun placeholdersAndDeceptiveSitesCannotBlockSelectedBrowserBridge(){
        for(candidate in listOf(
            "", "#", "javascript:void(0)",
            "https://m.4khd.com/45ycnl",
            "https://www.terabox.com.evil.example/s/1fake",
            "https://cdn.4khd.com/advert.jpg#tracking",
            "http://www.terabox.com/s/1insecure"
        )){
            assertFalse(candidate,client.isReal4khdBridge(candidate))
        }
    }
}
