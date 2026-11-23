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

    @Test fun onlyPublisherAttestedSharesAndFirstPartyArchivesQualify(){
        // A real 4KHD bridge currently publishes /s/<share-id>. General
        // TeraBox home/help links in its scripts must never win the scan.
        assertTrue(client.isReal4khdBridge(
            "https://www.terabox.com/s/1me4uCqbx20DyhHcItQaShQ"))
        assertTrue(client.isReal4khdBridge(
            "https://www.terabox.com/sharing/link?surl=shareCodeABC123"))
        assertTrue(client.isReal4khdBridge(
            "https://cdn.4khd.com/archives/selected-original.rar"))
        for(unrelated in listOf(
            "https://www.terabox.com/",
            "https://www.terabox.com/help",
            "https://www.terabox.com/login",
            "https://www.terabox.com/sharing/link",
            "https://www.terabox.com/wap/share/filelist?foo=bar",
            "https://thirdparty.example/files/unrelated.zip",
            "https://cdn.4khd.com/preview.jpg",
            "https://www.4khd.com/content/14/preview.mp4"
        )) assertFalse("Not an official 4KHD original: "+unrelated,
            client.isReal4khdBridge(unrelated))
    }

    @Test fun bridgeScriptSkipsGenericHostLinksAndKeepsExactPublicShare(){
        val script="""<script>const site="https://www.terabox.com/";
            const help="https://www.terabox.com/help";
            const real="https://www.terabox.com/s/1me4uCqbx20DyhHcItQaShQ";
            const ad="https://unrelated.example/unverified.zip";</script>"""
        assertEquals("https://www.terabox.com/s/1me4uCqbx20DyhHcItQaShQ",
            client.extractTeraBoxUrl(script))
        assertNull(client.extractTeraBoxUrl(
            """<script>const home="https://www.terabox.com/";</script>"""))
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
