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
            val request=(URL(media).openConnection() as HttpURLConnection)
            request.instanceFollowRedirects=false
            request.connectTimeout=12_000
            request.readTimeout=16_000
            request.setRequestProperty("User-Agent",ua)
            request.setRequestProperty("Referer",player)
            request.setRequestProperty("Range","bytes=0-255")
            request.setRequestProperty("Accept-Encoding","identity")
            try{
                assertEquals("Live source did not support real byte ranges",
                    206,request.responseCode)
                assertEquals("video/mp4",
                    request.contentType.substringBefore(';').lowercase())
                assertTrue(request.getHeaderField("Content-Range")
                    .orEmpty().startsWith("bytes 0-255/"))
                val first=request.inputStream.use{it.readNBytes(256)}
                assertEquals(256,first.size)
                assertEquals("ftyp",String(first,4,4,Charsets.US_ASCII))
                println("REAL_FULLPORNER_SELECTED_QUALITY_HOST="+URI(media).host+
                    " source_bytes=256 range=206 mp4=true")
            }finally{request.disconnect()}
        }
    }
}
