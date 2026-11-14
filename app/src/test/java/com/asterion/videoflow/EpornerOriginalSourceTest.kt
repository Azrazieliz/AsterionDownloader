package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class EpornerOriginalSourceTest {
    private val video="https://www.eporner.com/video-AcCjSXiLGwV/example-title/"
    @Test fun videoUrlMapsToOfficialSameSitePlayer(){
        assertEquals("AcCjSXiLGwV",EpornerOriginalSource.videoId(video))
        assertEquals("https://www.eporner.com/embed/AcCjSXiLGwV/",
            EpornerOriginalSource.officialEmbed(video))
        assertEquals("AcCjSXiLGwV",EpornerOriginalSource.videoId(
            "https://www.eporner.com/hd-porn/AcCjSXiLGwV/example-title/"))
        assertNull(EpornerOriginalSource.videoId(
            "https://www.eporner.com.evil.test/video-AcCjSXiLGwV/"))
        assertNull(EpornerOriginalSource.videoId("http://www.eporner.com/video-AcCjSXiLGwV/"))
    }
    @Test fun verifiedSessionUsesOfficialPlayerFirst(){
        val urls=EpornerOriginalSource.videoCandidates(video,true)
        assertEquals("https://www.eporner.com/embed/AcCjSXiLGwV/",urls[0])
        assertEquals(video,urls[1])
    }
    @Test fun publicVideoCanUseCanonicalWatchUrlWithoutAccount(){
        assertEquals(listOf("https://www.eporner.com/embed/AcCjSXiLGwV/",video),
            EpornerOriginalSource.videoCandidates(video,false))
        assertEquals("https://www.eporner.com/video-AcCjSXiLGwV/",
            EpornerOriginalSource.canonicalUrl(video))
    }
    @Test fun officialPublishedRuntimeCanValidateRealShortCompleteOriginal(){
        val accepted=FullLengthVideoPolicy.assess("Example original",
            "Example original",video,15L,15L,publisherVerified=true)
        assertEquals(FullLengthVideoPolicy.Verdict.ACCEPT,accepted.verdict)
        val unverified=FullLengthVideoPolicy.assess("Example original",
            "Example original",video,15L,15L)
        assertEquals(FullLengthVideoPolicy.Verdict.ACCEPT,unverified.verdict)
        val trailer=FullLengthVideoPolicy.assess("Example teaser",
            "Example teaser",video,15L,15L,publisherVerified=true)
        assertEquals(FullLengthVideoPolicy.Verdict.REJECT_SHORT_VERSION,
            trailer.verdict)
    }
    @Test fun originalMustMatchOfficialDuration(){
        val result=FullLengthVideoPolicy.assess("Published original",
            "Published original",video,40L,180L,publisherVerified=true)
        assertEquals(FullLengthVideoPolicy.Verdict.ACCEPT,result.verdict)
    }
}
