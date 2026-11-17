package com.asterion.videoflow

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Opt-in, genuinely live first-party original resolver acceptance.
 * Not included as a claimed pass in offline JVM tests. Explicit run:
 * ASTERION_LIVE_PROVIDERS=1 ... --tests LiveFullPornerPublishedOriginalTest.
 */
class LiveFullPornerPublishedOriginalTest {
    @Test fun selectedPublisherWatchAndPlayerResolveRealMP4Ranges(){
        Assume.assumeTrue("Only runs with real publisher opt-in",
            System.getenv("ASTERION_LIVE_PROVIDERS")=="1")
        val watch="https://fullporner.com/watch/6af6fbcd2f7835591446cb0f"
        val ua=NativeMediaDownloader.USER_AGENT
        assertTrue(FullPornerOriginalSource.selectedPage(watch))
        val doc=Jsoup.connect(watch).userAgent(ua).timeout(20_000)
            .followRedirects(true).get()
        val frames=doc.select("iframe[src]").mapNotNull {
            it.absUrl("src").takeIf(FullPornerOriginalSource::selectedPlayer)
        }.distinct()
        assertEquals("No unique selected publisher player on live article",
            1,frames.size)
        val player=frames.single()
        val script=Jsoup.connect(player).userAgent(ua).referrer(watch)
            .timeout(20_000).get().outerHtml()
        val candidates=FullPornerOriginalSource
            .publishedQualities(watch,player,script)
        assertEquals("The selected publisher advertises three real quality URLs",
            3,candidates.size)
        for(media in candidates){
            assertTrue(FullPornerOriginalSource.verifiedMedia(
                watch,player,media))
            assertTrue(NativeMediaDownloader.isNativeEligible(
                watch,media,verifiedMp4=true))
            // The official /vid/ URL can redirect to its signed CDN.
            // Follow exactly the same bounded redirects as the app's
            // NativeMediaDownloader, retaining the selected-player Referer.
            // A publisher redirect is not a media failure or a final file.
            var next=media
            var verified=false
            for(hop in 0..5){
                val request=(URL(next).openConnection() as HttpURLConnection)
                request.instanceFollowRedirects=false
                request.connectTimeout=12_000
                request.readTimeout=16_000
                request.setRequestProperty("User-Agent",ua)
                request.setRequestProperty("Referer",player)
                request.setRequestProperty("Range","bytes=0-255")
                request.setRequestProperty("Accept-Encoding","identity")
                try{
                    val status=request.responseCode
                    if(status in 300..399){
                        assertTrue("Selected media exceeded redirect limit",hop<5)
                        val location=request.getHeaderField("Location")
                        assertFalse("Media redirect missing destination",
                            location.isNullOrBlank())
                        val resolved=URI(next).resolve(location).normalize()
                        assertEquals("https",resolved.scheme)
                        assertNull(resolved.userInfo)
                        next=resolved.toString()
                        continue
                    }
                    assertEquals("Real selected media did not return 206",
                        206,status)
                    assertEquals("video/mp4",
                        request.contentType.substringBefore(';').lowercase())
                    assertTrue(request.getHeaderField("Content-Range")
                        .orEmpty().startsWith("bytes 0-255/"))
                    val first=request.inputStream.use{it.readNBytes(256)}
                    assertEquals(256,first.size)
                    assertEquals("ftyp",String(first,4,4,Charsets.US_ASCII))
                    println("REAL_FULLPORNER_FINAL_MEDIA_HOST="+URI(next).host+
                        " redirect_hops="+hop+" source_bytes=256 range=206 mp4=true")
                    verified=true
                    break
                }finally{request.disconnect()}
            }
            assertTrue("No final MP4 media reached",verified)
        }
    }
}
