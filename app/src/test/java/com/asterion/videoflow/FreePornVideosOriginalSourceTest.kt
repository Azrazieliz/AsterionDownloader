package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class FreePornVideosOriginalSourceTest {
    private val page="https://www.freepornvideos.xxx/videos/93823063/conception-deception/"
    private val link="https://www.freepornvideos.xxx/get_file/8512/signed/93823063/93823063_720m.mp4/?download=true"
    private fun html(meta:String="2636",json:String="PT43M56S")="""
        <html><head>
          <meta itemprop="duration" content="$meta">
          <script type="application/ld+json">
            {"@type":"VideoObject","duration":"$json"}
          </script></head><body>
          <a href="$link">Download</a>
          <a href="https://advertising.example/video.mp4">Advertisement</a>
        </body></html>
    """.trimIndent()

    @Test fun acceptsExactPageWithOriginalAndMatchingDuration(){
        val result=FreePornVideosOriginalSource.parse(html(),page)
        assertNotNull(result)
        assertEquals(2636L,result!!.durationSeconds)
        assertEquals(link,result.mediaUrl)
    }
    @Test fun conflictingDurationLeavesPreflightMandatory(){
        assertNull(FreePornVideosOriginalSource.parse(html("200","PT43M56S"),page)
            ?.durationSeconds)
        assertNull(FreePornVideosOriginalSource.parse(html("n/a","bad"),page)
            ?.durationSeconds)
    }
    @Test fun rejectsCrossHostAndPreviewAndNonVideoPage(){
        val bad=html().replace(link,"https://freepornvideos.xxx.attacker.test/get_file/x.mp4")
        assertNull(FreePornVideosOriginalSource.parse(bad,page))
        val trailer=html().replace("93823063_720m.mp4","preview_720.mp4")
        assertNull(FreePornVideosOriginalSource.parse(trailer,page))
        assertFalse(FreePornVideosOriginalSource.isExactPage(
            "https://www.freepornvideos.xxx/pornstars/performer/"))
    }
    @Test fun acceptsDirectLinkWithoutInventingDuration(){
        val result=FreePornVideosOriginalSource.parse(
            "<a href=\"$link\">Download</a>",page)
        assertNotNull(result)
        assertNull(result!!.durationSeconds)
    }
}
