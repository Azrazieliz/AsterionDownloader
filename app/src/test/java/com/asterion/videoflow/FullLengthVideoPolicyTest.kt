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
    @Test fun trustedFullReferenceMustHaveSubstantialDuration(){
        val short=FullLengthVideoPolicy.assess("Studio Actress Scene",
            "Studio Actress Scene","https://watchporn.to/video/12/example",15*60L,null)
        assertEquals(FullLengthVideoPolicy.Verdict.HOLD_FOR_REFERENCE,short.verdict)
        val long=FullLengthVideoPolicy.assess("Studio Actress Scene",
            "Studio Actress Scene","https://watchporn.to/video/12/example",50*60L,null)
        assertEquals(FullLengthVideoPolicy.Verdict.ACCEPT,long.verdict)
    }
    @Test fun titlesNormalizeWithoutMergingUnrelatedWork(){
        assertEquals(FullLengthVideoPolicy.sceneKey("Performer A Full Scene 1080p"),
            FullLengthVideoPolicy.sceneKey("Performer A Full Scene 4K"))
        assertNotEquals(FullLengthVideoPolicy.sceneKey("Performer A Original Scene"),
            FullLengthVideoPolicy.sceneKey("Performer B Original Scene"))
    }
}
