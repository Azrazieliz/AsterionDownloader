package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class WatchPornOriginalSourceTest {
    private val page="https://watchporn.to/video/169519/example-original/"
    private val media="https://watchporn.to/get_file/1234/original.mp4/?token=signed"
    private fun html(meta:String="1557",iso:String="PT0H25M57S")="""
        <html><head>
          <meta itemprop="duration" content="$meta"/>
          <script type="application/ld+json">
            {"@type":"VideoObject","duration":"$iso"}
          </script>
        </head><body><script>
          video_alt_url: '$media'
        </script></body></html>
    """.trimIndent()

    @Test fun acceptsPublisherVerifiedOriginalWithMatchingIndependentRuntime(){
        val result=WatchPornOriginalSource.parse(html(),page)
        assertNotNull(result)
        assertEquals(1557L,result!!.durationSeconds)
        assertEquals(media,result.mediaUrl)
    }
    @Test fun acceptsCanonicalWwwSelectedPageAndFirstPartyMedia(){
        val wwwPage="https://www.watchporn.to/video/169519/example-original/"
        val wwwMedia=media.replace("https://watchporn.to/",
            "https://www.watchporn.to/")
        val result=WatchPornOriginalSource.parse(
            html().replace(media,wwwMedia),wwwPage)
        assertNotNull(result)
        assertEquals(wwwMedia,result!!.mediaUrl)
    }
    @Test fun wwwDoesNotAllowUnrelatedSimilarLookingDomains(){
        assertFalse(WatchPornOriginalSource.isExactPage(
            "https://watchporn.to.attacker.example/video/169519/example-original/"))
        assertFalse(WatchPornOriginalSource.isExactPage(
            "https://www.watchporn.to.attacker.example/video/169519/example-original/"))
    }
    @Test fun rejectsDisagreeingDurationEvidence(){
        assertNull(WatchPornOriginalSource.parse(html("1557","PT0H21M10S"),page))
        assertNull(WatchPornOriginalSource.parse(html("0"),page))
        assertNull(WatchPornOriginalSource.parse(html("1557","invalid"),page))
    }
    @Test fun rejectsCategoryPageAndUnapprovedHost(){
        assertNull(WatchPornOriginalSource.parse(html(),
            "https://watchporn.to/models/performer/"))
        assertFalse(WatchPornOriginalSource.isExactPage(
            "https://watchporn.to.attacker.test/video/169519/example/"))
    }
    @Test fun fullPolicyStillRejectsPromotionalClip(){
        val decision=FullLengthVideoPolicy.assess(
            "Example teaser", "Example teaser",page,1557L,1557L,
            publisherVerified=true)
        assertEquals(FullLengthVideoPolicy.Verdict.REJECT_SHORT_VERSION,decision.verdict)
    }
}
