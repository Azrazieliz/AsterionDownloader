package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import org.jsoup.Jsoup

/** Real publisher video identity is mandatory for cover previews. */
class Rule34PosterEvidenceTest {
    private val page="https://rule34video.com/video/4727779/female-v-x-bbc-reverse-cowgirl/"
    private val poster="https://rule34video.com/contents/videos_screenshots/4727000/4727779/preview.jpg"

    @Test fun selectedVideoFlashvarsCanSupplyPosterWithoutOgMetadata(){
        val html="""<html><main><div id="kt_player"></div>
            <script>var flashvars = {
              video_id: '4727779',
              video_url: 'https://rule34video.com/get_file/signed?download=true',
              preview_url: '$poster',
              preview_url1: 'https://rule34video.com/contents/videos_screenshots/4727000/4727779/preview_720p.mp4.jpg'
            };
            kt_player('kt_player', 'https://rule34video.com/kt_player.js');</script>
            <aside class="related"><img src=
            "https://rule34video.com/contents/videos_screenshots/4727000/4727221/preview.jpg"/></aside>
            </main></html>"""
        assertEquals(poster,Rule34PosterEvidence.selectedPoster(page,html))
        assertEquals(poster,Rule34PosterEvidence.selectedFlashvarsPoster(page,html))
        assertNull(Rule34PosterEvidence.selectedFlashvarsPoster(
            page,html.replace("video_id: '4727779'","video_id: '4727221'")))
        assertNull(Rule34PosterEvidence.selectedFlashvarsPoster(
            page,html.replace("/4727779/preview.jpg","/4727221/preview.jpg")))
        assertNull(Rule34PosterEvidence.selectedFlashvarsPoster(
            "https://rule34video.com/",html))
    }

    @Test fun unrelatedPlayerAndThirdPartyPreviewCannotBeAccepted(){
        val unrelated="""<script>var flashvars = {
            video_id: '4727779',
            preview_url: 'https://ads.invalid/promo.jpg'
            };kt_player('kt_player');</script>"""
        assertNull(Rule34PosterEvidence.selectedFlashvarsPoster(page,unrelated))
        val sameHostWrongItem="""<script>var flashvars = {
            video_id: '4727779',
            preview_url: 'https://rule34video.com/contents/videos_screenshots/4727000/4727221/preview.jpg'
            };kt_player('kt_player');</script>"""
        assertNull(Rule34PosterEvidence.selectedFlashvarsPoster(page,sameHostWrongItem))
    }

    @Test fun realSelectedVideoPublishesFetchableMatchingImage(){
        assumeTrue("Only opt-in live source HTTP probe",
            System.getenv("ASTERION_LIVE_PROVIDERS")=="1")
        val ua="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
        val doc=Jsoup.connect(page).userAgent(ua).timeout(20_000).get()
        val url=Rule34PosterEvidence.selectedFlashvarsPoster(page,doc.outerHtml())
            ?:error("Selected publisher did not expose a matching player preview")
        assertEquals("Live selected poster must match video ID",poster,url)
        val conn=URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout=12_000
        conn.readTimeout=18_000
        conn.setRequestProperty("User-Agent",ua)
        conn.setRequestProperty("Referer",page)
        conn.setRequestProperty("Accept-Encoding","identity")
        try{
            assertTrue("Poster did not return successful HTTP",conn.responseCode in 200..206)
            val sample=conn.inputStream.use{it.readNBytes(256)}
            assertTrue("Publisher returned nonimage first bytes",
                sample.size>=12 && (
                    sample[0]==0xff.toByte() && sample[1]==0xd8.toByte() ||
                    sample[0]==0x89.toByte() && sample[1]==0x50.toByte() ||
                    String(sample,0,4)=="RIFF"))
            println("REAL_RULE34_SELECTED_POSTER_MATCHED_ID=4727779 HTTP="+conn.responseCode+
                " bytes="+sample.size)
        }finally{conn.disconnect()}
    }
}
