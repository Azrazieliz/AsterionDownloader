package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class FullLengthVideoPolicyTest {
    private val url="https://www.porngo.com/scene/long-scene"
    @Test fun rejectsShortenedSceneWhenReferenceIsFiftyMinutes(){
        val answer=FullLengthVideoPolicy.assess("Studio Actress Long Scene",
            "Studio Actress Long Scene",url,15*60L,50*60L)
        assertEquals(FullLengthVideoPolicy.Verdict.REJECT_SHORT_VERSION,answer.verdict)
    }
    @Test fun acceptsSameSceneMatchingReferenceRuntime(){
        val answer=FullLengthVideoPolicy.assess("Studio Actress Long Scene",
            "Studio Actress Long Scene",url,50*60L,50*60L)
        assertEquals(FullLengthVideoPolicy.Verdict.ACCEPT,answer.verdict)
    }
    @Test fun neverAcceptsTeaserEvenFromReferenceHost(){
        val answer=FullLengthVideoPolicy.assess("Studio Actress Scene trailer",
            "Studio Actress Scene", "https://watchporn.to/video/332/scene",3600L,3600L)
        assertEquals(FullLengthVideoPolicy.Verdict.REJECT_SHORT_VERSION,answer.verdict)
    }
    @Test fun holdsUnknownDurationAndUncorroboratedTube(){
        val unknown=FullLengthVideoPolicy.assess("Studio Actress Scene",
            "Studio Actress Scene",url,null,null)
        assertEquals(FullLengthVideoPolicy.Verdict.HOLD_FOR_REFERENCE,unknown.verdict)
        val preview=FullLengthVideoPolicy.assess("Studio Actress Scene",
            "Studio Actress Scene",url,800L,null)
        assertEquals(FullLengthVideoPolicy.Verdict.HOLD_FOR_REFERENCE,preview.verdict)
    }
    @Test fun manualOriginalRequestCanProceedWithoutExistingReference(){
        val direct="https://www.pornhub.com/view_video.php?viewkey=ab"
        val decision=FullLengthVideoPolicy.assess(
            "Original Scene","Original Scene",direct,
            1800L,null,userExplicitlyRequested=true)
        assertEquals(FullLengthVideoPolicy.Verdict.ACCEPT,decision.verdict)
        val automatic=FullLengthVideoPolicy.assess(
            "Original Scene","Original Scene",direct,1800L,null)
        assertEquals(FullLengthVideoPolicy.Verdict.HOLD_FOR_REFERENCE,automatic.verdict)
    }
    @Test fun manualChoiceNeverAuthorizesPreviewOrUnknownRuntime(){
        val url="https://www.pornhub.com/view_video.php?viewkey=ab"
        assertEquals(FullLengthVideoPolicy.Verdict.REJECT_SHORT_VERSION,
            FullLengthVideoPolicy.assess(
                "Original Scene teaser","Original Scene",url,900L,null,
                userExplicitlyRequested=true).verdict)
        assertEquals(FullLengthVideoPolicy.Verdict.HOLD_FOR_REFERENCE,
            FullLengthVideoPolicy.assess(
                "Original Scene","Original Scene",url,null,null,
                userExplicitlyRequested=true).verdict)
    }
    @Test fun ownerTrustedFullVideoSourcesAreRecognized(){
        val designated=listOf(
            "https://watchporn.to/video/12/example",
            "https://www.freepornvideos.xxx/video/example",
            "https://fullporner.com/watch/6af501862f7835591446c7d3",
            "https://archivebate.com/videos/example",
            "https://xxxi.porn/video/example",
            "https://helloporn.com/video/example",
            "https://notfans.com/video/example",
            "https://www.eporner.com/video-ABC12345/"
        )
        for(source in designated) {
            val result=FullLengthVideoPolicy.assess(
                "Studio Actress Scene","Studio Actress Scene",source,50*60L,null)
            assertEquals(FullLengthVideoPolicy.Verdict.ACCEPT,result.verdict)
        }
        for(source in listOf(
            "https://www.porngo.com/video/example",
            "https://www.pornhub.com/view_video.php?viewkey=ab",
            "https://www.example.com/video/example"
        ))for(seconds in listOf(8*60L,20*60L,50*60L,90*60L)){
            val result=FullLengthVideoPolicy.assess(
                "Studio Actress Scene","Studio Actress Scene",source,seconds,null)
            assertEquals("Unverified source must hold: "+source+" "+seconds,
                FullLengthVideoPolicy.Verdict.HOLD_FOR_REFERENCE,result.verdict)
        }
    }
    @Test fun trustedProviderAcceptsUnreportedRuntimeButRejectsExplicitExcerpt(){
        assertEquals(FullLengthVideoPolicy.Verdict.REJECT_SHORT_VERSION,
            FullLengthVideoPolicy.assess("Studio Actress teaser",
                "Studio Actress","https://watchporn.to/video/12/a",3000,null).verdict)
        assertEquals(FullLengthVideoPolicy.Verdict.ACCEPT,
            FullLengthVideoPolicy.assess("Studio Actress",
                "Studio Actress","https://watchporn.to/video/12/a",null,null).verdict)
        assertEquals(FullLengthVideoPolicy.Verdict.REJECT_SHORT_VERSION,
            FullLengthVideoPolicy.assess("Example clip",
                "Example clip","https://www.eporner.com/video-ABC12345/",null,null).verdict)
    }
    @Test fun tinyCutIsRejectedAgainstVerifiedOriginal(){
        val result=FullLengthVideoPolicy.assess("Studio Actress Scene",
            "Studio Actress Scene","https://www.porngo.com/scene/ab",49*60L,50*60L)
        assertEquals(FullLengthVideoPolicy.Verdict.REJECT_SHORT_VERSION,result.verdict)
        val timing=FullLengthVideoPolicy.assess("Studio Actress Scene",
            "Studio Actress Scene","https://www.porngo.com/scene/ab",50*60L-5L,50*60L)
        assertEquals(FullLengthVideoPolicy.Verdict.ACCEPT,timing.verdict)
    }
    @Test fun titlesNormalizeWithoutMergingUnrelatedWork(){
        assertEquals(FullLengthVideoPolicy.sceneKey("Performer A Full Scene 1080p"),
            FullLengthVideoPolicy.sceneKey("Performer A Full Scene 4K"))
        assertNotEquals(FullLengthVideoPolicy.sceneKey("Performer A Original Scene"),
            FullLengthVideoPolicy.sceneKey("Performer B Original Scene"))
    }
    @Test fun releaseIdMatchesCrossLanguageTitlesWithoutMergingOtherReleases(){
        assertEquals(FullLengthVideoPolicy.sceneKey("ABP-123 / Actress / English"),
            FullLengthVideoPolicy.sceneKey("日本語 ABP_123 完全版"))
        assertNotEquals(FullLengthVideoPolicy.sceneKey("ABP-123"),FullLengthVideoPolicy.sceneKey("ABP-124"))
    }

    @Test fun genericEpisodeNumbersAreNotMistakenForUniqueReleaseIds(){
        assertNotEquals(FullLengthVideoPolicy.sceneKey("Series Alpha episode 50"),
            FullLengthVideoPolicy.sceneKey("Series Beta episode 50"))
    }

}
