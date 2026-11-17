package com.asterion.videoflow

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Explicitly opt-in HTTP source verification: real Archivebate watch/16458050
 * -> publisher single selected Mixdrop -> same player canonical redirect ->
 * its packed MDCore.wurl -> only exact real 206 video/mp4 ftyp bytes.
 * A 2GB partial source is NOT a completed video or Android MediaStore receipt.
 */
class LiveArchivebatePublishedOriginalTest {
    private val watch="https://archivebate.com/watch/16458050"

    @Test fun actualSelectedPublisherMp4ReturnsOriginalRangeBytes(){
        assumeTrue("Set ASTERION_LIVE_PROVIDERS=1 to run the real public source test",
            System.getenv("ASTERION_LIVE_PROVIDERS")=="1")
        val userAgent=NativeMediaDownloader.USER_AGENT
        val watchPage=Jsoup.connect(watch).userAgent(userAgent)
            .timeout(25_000).maxBodySize(1_000_000).get()
        val frames=PublisherOriginalExtractors.fromHtml(
            watch,watchPage.outerHtml()).filter {
                ArchivebateSelectedMedia.isSelectedEmbed(watch,it.url)
            }
        assertEquals("Selected watch published ambiguous Mixdrop iframe",1,frames.size)
        val frame=frames.single().url
        val first=Jsoup.connect(frame).userAgent(userAgent).referrer(watch)
            .timeout(25_000).followRedirects(false).ignoreHttpErrors(true).execute()
        val canonical=if(first.statusCode() in 300..399) {
            val target=URI(frame).resolve(first.header("Location")).toString()
            assertTrue("Redirect changed selected player ID",
                ArchivebateSelectedMedia.selectedRedirect(frame,target))
            target
        }else frame
        val page=if(canonical==frame)first.body() else
            Jsoup.connect(canonical).userAgent(userAgent).referrer(watch)
                .timeout(25_000).maxBodySize(500_000).get().outerHtml()
        val source=ArchivebateSelectedMedia.publishedPlayerHtml(
            watch,frame,canonical,page)
        assertNotNull("Selected canonical player exposed no attributable MDCore.wurl",source)
        assertTrue(ArchivebateSelectedMedia.isSelectedMedia(source!!))
        val conn=URL(source).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects=false
        conn.connectTimeout=25_000
        conn.readTimeout=25_000
        conn.setRequestProperty("User-Agent",userAgent)
        conn.setRequestProperty("Referer",canonical)
        conn.setRequestProperty("Range","bytes=0-1023")
        conn.setRequestProperty("Accept-Encoding","identity")
        try {
            assertEquals("Selected media did not return original bytes",206,conn.responseCode)
            assertEquals("video/mp4",conn.contentType.substringBefore(";").trim())
            assertTrue("Declared selected original byte range missing",
                Regex("""bytes 0-1023/[0-9]+""")
                    .matches(conn.getHeaderField("Content-Range").orEmpty()))
            val bytes=conn.inputStream.use{it.readNBytes(1024)}
            assertEquals(1024,bytes.size)
            assertEquals("ftyp",String(bytes,4,4,Charsets.US_ASCII))
            println("ARCHIVEBATE_SELECTED_MDCORE_ORIGINAL_RANGE_PASS=1")
        }finally{conn.disconnect()}
    }
}
