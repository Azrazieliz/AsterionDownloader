package com.asterion.videoflow

import org.json.JSONObject
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Opt-in public first-party AJAX proof. No cookies or account bypass.
 * Resolves the actual selected post ID+nonce via the SAME Kotlin resolver
 * and checks only the first 256 selected MP4 bytes, never a recommendation.
 */
class LiveThreeDHentaiSignedOriginalTest {
    @Test fun exactSelectedPostNonceResolvesPublisherSignedMP4(){
        Assume.assumeTrue(System.getenv("ASTERION_LIVE_PROVIDERS")=="1")
        val selected="https://3dhentaix.com/ntr-compilation/"
        assertTrue(ThreeDHentaiSignedOriginal.isSelectedArticle(selected))
        val source=Jsoup.connect(selected)
            .userAgent(NativeMediaDownloader.USER_AGENT).timeout(20_000)
            .maxBodySize(1_800_000).get()
        val published=ThreeDHentaiSignedOriginal.publishedConfig(source,selected)
        assertNotNull("Selected article has no single signed-player config",published)
        val config=published!!
        val xhr=Jsoup.connect("https://3dhentaix.com/wp-admin/admin-ajax.php")
            .userAgent(NativeMediaDownloader.USER_AGENT)
            .method(Connection.Method.POST)
            .data("action","tdx_get_signed_playback_url")
            .data("post_id",config.postId.toString())
            .data("nonce",config.nonce)
            .header("Referer",selected)
            .header("Origin","https://3dhentaix.com")
            .ignoreHttpErrors(true).ignoreContentType(true)
            .followRedirects(false).timeout(16_000).execute()
        assertEquals(200,xhr.statusCode())
        val json=JSONObject(xhr.body())
        assertTrue("Publisher rejected exact player post/nonce",json.optBoolean("success"))
        val link=json.optJSONObject("data")?.optString("url").orEmpty()
        assertTrue("Publisher did not attest first-party signed media",
            ThreeDHentaiSignedOriginal.firstPartyFormat(selected,link))
        assertTrue(NativeMediaDownloader.isNativeEligible(
            selected,link,verifiedMp4=true))
        val media=URL(link).openConnection() as HttpURLConnection
        media.instanceFollowRedirects=false
        media.connectTimeout=12_000
        media.readTimeout=16_000
        media.setRequestProperty("User-Agent",NativeMediaDownloader.USER_AGENT)
        media.setRequestProperty("Referer",selected)
        media.setRequestProperty("Range","bytes=0-255")
        media.setRequestProperty("Accept-Encoding","identity")
        try{
            assertEquals(206,media.responseCode)
            assertEquals("video/mp4",
                media.contentType.substringBefore(';').lowercase())
            assertTrue(media.getHeaderField("Content-Range")
                .orEmpty().startsWith("bytes 0-255/"))
            val first=media.inputStream.use{it.readNBytes(256)}
            assertEquals("ftyp",String(first,4,4,Charsets.US_ASCII))
            println("REAL_SIGNED_SELECTED_POST_ID="+config.postId+
                " FIRST_PARTY_MP4_BYTES=256 SIGNATURE=ftyp")
        }finally{media.disconnect()}
    }
}
