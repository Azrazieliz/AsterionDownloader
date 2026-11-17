package com.asterion.videoflow

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class RoshyPackedOriginalSourceTest {
    private val selected="https://roshy.tv/video/rctd-759-english-subtitles-decensored-rocket-18th-anniversary-user-request-festival-lesbian-rough-fight-extreme/"
    private val frame="https://kamehaus.net/e/242urr647yem"
    private val published="/stream/CQuNCWeFg-Rg9CL5simUvg/kjhhiuahiuhgihdf/1794952524/76350888/master.m3u8"
    private fun decoded(url:String=published,id:String="242urr647yem")=
        """var uas=[];var links={"hls4":"$url","hls2":"https://test.premilkyway.com/hls2/01/stream.m3u8?token=official","hls3":""};
           var seekPosition='tt$id';
           jwplayer("vplayer").setup({debug:"1",sources:[{file:links.hls4||links.hls3||links.hls2,type:"hls"}]});
        """

    @Test fun publisherHlsBoundToExactSelectedRoshyPostAndEmbed(){
        assertTrue(RoshyPackedOriginalSource.selectedEmbed(selected,frame))
        val src=RoshyPackedOriginalSource.fromDecoded(selected,frame,decoded())
        assertEquals("https://kamehaus.net$published",src)
        assertNull(RoshyPackedOriginalSource.fromDecoded(selected,frame,
            decoded(id="another123456")))
        assertNull(RoshyPackedOriginalSource.fromDecoded(selected,
            "https://kamehaus.net/e/999999999999",decoded()))
        assertNull(RoshyPackedOriginalSource.fromDecoded(selected,
            "https://ads.invalid/e/242urr647yem",decoded()))
        assertNull(RoshyPackedOriginalSource.fromDecoded(
            "https://hentaihaven.xxx/watch/example/",frame,decoded()))
    }
    @Test fun rejectUnpublishedOrUnrelatedHlsPlayerAndAd(){
        assertNull(RoshyPackedOriginalSource.fromDecoded(selected,frame,
            decoded(url="https://ads.invalid/other-scene.m3u8")))
        assertNull(RoshyPackedOriginalSource.fromDecoded(selected,frame,
            decoded(url="/stream/other-scene/master.m3u8")))
        assertNull(RoshyPackedOriginalSource.fromDecoded(selected,frame,
            decoded().replace("jwplayer(\"vplayer\")","jwplayer(\"adplayer\")")))
        assertNull(RoshyPackedOriginalSource.fromDecoded(selected,frame,
            decoded().replace("var links=","var unrelated=")))
    }
    @Test fun liveSelectedPublisherPackedPlayerExposesRealHlsBytes(){
        assumeTrue("Dedicated, opt-in real publisher HTTPS test",
            System.getenv("ASTERION_LIVE_PROVIDERS")=="1")
        val ua=NativeMediaDownloader.USER_AGENT
        val watch=Jsoup.connect(selected).userAgent(ua).timeout(25_000)
            .maxBodySize(1_800_000).get()
        val options=SiteSpecificOriginalSources.roshy(watch,selected)
        val frames=options.filter{
            RoshyPackedOriginalSource.selectedEmbed(selected,it.url)
        }
        assertEquals("Publisher did not choose one original stream",1,frames.size)
        val actual=frames.single().url
        val player=Jsoup.connect(actual).userAgent(ua).referrer(selected)
            .timeout(25_000).maxBodySize(1_500_000).get()
        val hls=RoshyPackedOriginalSource.selectedPlaylist(
            selected,actual,player.outerHtml())
        assertNotNull("Exact player HLS source not resolved",hls)
        assertTrue(hls!!.startsWith("https://kamehaus.net/stream/"))
        val req=URL(hls).openConnection() as HttpURLConnection
        req.connectTimeout=18_000
        req.readTimeout=25_000
        req.setRequestProperty("User-Agent",ua)
        req.setRequestProperty("Referer",actual)
        req.setRequestProperty("Accept-Encoding","identity")
        try{
            assertEquals("Official selected HLS did not open",200,req.responseCode)
            val body=req.inputStream.use{it.readNBytes(100_000)}
            assertTrue("Selected source is not an actual HLS playlist",
                String(body,Charsets.UTF_8).trimStart().startsWith("#EXTM3U"))
            println("REAL_ROSHY_SELECTED_HLS=true playlist_bytes="+body.size+
                " original_player_host=kamehaus.net")
        }finally{req.disconnect()}
    }
}
