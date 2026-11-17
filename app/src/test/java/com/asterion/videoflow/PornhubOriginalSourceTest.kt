package com.asterion.videoflow

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Exact selected video quality variants; do not harvest recommendations. */
class PornhubOriginalSourceTest {
    private val selected="https://www.pornhub.com/view_video.php?viewkey=phabcde123"
    private val hls="https://em-h.phncdn.com/videos/abc123/sample_720.m3u8?token=publisher"
    private fun published(url:String=hls, extra:String="")="""<html>
      <div id="player"></div><aside class="related">
      <video src="https://ads.invalid/unrelated.mp4"></video></aside>
      <script>
       var flashvars_12345678901234567890 = {
         "video_unavailable":"false",
         "mediaDefinitions":[
           {"format":"hls","quality":"240",
             "videoUrl":"https://em-h.phncdn.com/videos/abc123/sample_240.m3u8?t=one"},
           {"format":"hls","quality":"720","videoUrl":"$url"},
           {"format":"mp4","quality":"1080",
             "videoUrl":"https://ads.invalid/popup.mp4"}]
       };
       $extra
      </script></html>"""

    @Test fun exactSelectedWatchReturnsHighestFirstOnlyPublisherHls(){
        val results=PornhubOriginalSource.published(selected,published())
        assertEquals(2,results.size)
        assertEquals(720,results[0].height)
        assertEquals(hls,results[0].url)
        assertEquals(240,results[1].height)
    }
    @Test fun invalidWatchRouteAndUnrelatedAdOrHostAreRejected(){
        assertTrue(PornhubOriginalSource.published(
            "https://www.pornhub.com/",published()).isEmpty())
        assertTrue(PornhubOriginalSource.published(
            "https://www.pornhub.com/view_video.php",published()).isEmpty())
        assertTrue(PornhubOriginalSource.published(
            "https://www.pornhub.com/view_video.php?viewkey=abc123&viewkey=xyz",
            published()).isEmpty())
        assertTrue(PornhubOriginalSource.published(
            "https://www.pornhub.com.evil.example/view_video.php?viewkey=phabcde123",
            published()).isEmpty())
        assertEquals(1,PornhubOriginalSource.published(
            selected,published("https://ads.invalid/a.m3u8")).size)
        assertEquals(1,PornhubOriginalSource.published(
            selected,published("https://em-h.phncdn.com.evil.example/a.m3u8")).size)
    }
    @Test fun twoIndependentFlashvarsPlayersAreAmbiguous(){
        val other="""var flashvars_3333333333333333 = {
          "mediaDefinitions":[{"format":"hls","quality":"1080",
          "videoUrl":"https://em-h.phncdn.com/videos/ads/ad_1080.m3u8"}]};"""
        assertTrue(PornhubOriginalSource.published(selected,
            published(extra=other)).isEmpty())
    }
    @Test fun exactSelectedPublisherRealHlsBytes(){
        assumeTrue("Explicit live test only",System.getenv("ASTERION_LIVE_PROVIDERS")=="1")
        val ua=NativeMediaDownloader.USER_AGENT
        val home="https://www.pornhub.com/"
        val root=Jsoup.connect(home).userAgent(ua)
            .timeout(25_000).maxBodySize(1_600_000).get()
        val articles=root.select("a[href*=view_video.php?viewkey=]")
            .mapNotNull{a->
                val url=runCatching{URI(home).resolve(a.attr("href"))}
                    .getOrNull()?:return@mapNotNull null
                val value=url.toString()
                if(value.startsWith("https://www.pornhub.com/view_video.php?viewkey="))
                    value else null
            }.distinct()
        assertTrue("No genuine publisher-selected watch",articles.isNotEmpty())
        val real=articles.first()
        val page=Jsoup.connect(real).userAgent(ua)
            .referrer(home).timeout(25_000).maxBodySize(1_800_000).get()
        val hls=PornhubOriginalSource.published(real,page.outerHtml())
        assertTrue("Selected real flashvars missing",hls.isNotEmpty())
        val highest=hls.first()
        val conn=URL(highest.url).openConnection() as HttpURLConnection
        conn.connectTimeout=20_000
        conn.readTimeout=25_000
        conn.setRequestProperty("User-Agent",ua)
        conn.setRequestProperty("Referer",real)
        conn.setRequestProperty("Accept-Encoding","identity")
        try{
            assertEquals(200,conn.responseCode)
            val data=conn.inputStream.use{it.readNBytes(2000)}
            assertTrue(String(data,Charsets.UTF_8).trimStart()
                .startsWith("#EXTM3U"))
            println("PORN_SELECTED_HLS_PUBLISHED=true")
            println("PORN_SELECTED_HIGHEST_QUALITY="+highest.height)
        }finally{conn.disconnect()}
    }
}
