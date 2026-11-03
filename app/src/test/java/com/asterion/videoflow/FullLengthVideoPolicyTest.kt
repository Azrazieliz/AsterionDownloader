package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class FullLengthVideoPolicyTest {
    private val url="https://eporner.com/hd-porn/long-scene"
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
    @Test fun noSourceCanSelfCertifyFullLength(){
        for(source in listOf(
            "https://watchporn.to/video/12/example",
            "https://www.freepornvideos.xxx/video/example",
            "https://www.porngo.com/video/example",
            "https://www.pornhub.com/view_video.php?viewkey=ab"
        )){
            for(seconds in listOf(8*60L,20*60L,50*60L,90*60L)){
                val result=FullLengthVideoPolicy.assess(
                    "Studio Actress Scene","Studio Actress Scene",source,seconds,null)
                assertEquals("Unverified source must hold: "+source+" "+seconds,
                    FullLengthVideoPolicy.Verdict.HOLD_FOR_REFERENCE,result.verdict)
            }
        }
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
}
