package com.azrael.galleryflow

import org.junit.Assert.*
import org.junit.Test

class CosplayTeleVideoCompletenessTest {
    private val article="https://cosplaytele.com/selected-gallery/"
    private fun media(id:String,kind:MediaKind,url:String)=MediaRef(
        SourceId.COSPLAYTELE,id,"gallery-one",0,url,url,article,
        kind=kind)
    private fun saved(id:String,url:String,name:String)=MediaRecord(
        SourceId.COSPLAYTELE,id,"gallery-one","site:all",0,url,
        "content://media/external/file/1",name,TransferState.COMPLETE,
        null,null,null)

    @Test fun photoReceiptCannotCompleteAdvertisedVideo(){
        val photo=media("photo",MediaKind.IMAGE,"https://cdn.example/photo.jpg")
        val result=CosplayTeleVideoCompleteness.audit(1,listOf(photo),
            listOf(saved("photo",photo.url,"photo.jpg")),setOf("photo"))
        assertFalse(result.complete)
        assertEquals(0,result.verifiedVideos)
        assertEquals(1,result.expectedVideos)
    }

    @Test fun verifiedSelectedVideoCompletesOnlyItsOwnClaim(){
        val video=media("video",MediaKind.VIDEO,"https://cdn.example/selected.mp4")
        assertTrue(CosplayTeleVideoCompleteness.audit(1,listOf(video),
            listOf(saved("video",video.url,"selected.mp4")),
            setOf("video")).complete)
        assertFalse(CosplayTeleVideoCompleteness.audit(2,listOf(video),
            listOf(saved("video",video.url,"selected.mp4")),
            setOf("video")).complete)
        assertFalse(CosplayTeleVideoCompleteness.audit(1,listOf(video),
            listOf(saved("video",video.url,"selected.mp4")),
            emptySet()).complete)
    }

    @Test fun onlySelectedArchiveVideoMembersCountNotForeignVideos(){
        val archive=media("archive",MediaKind.ARCHIVE,
            "https://mediafire.com/file/confirmed/original.zip")
        val own="archive:"+archive.normalizedUrl+"#set/video1.mp4"
        val other="archive:https://other.example/foreign.zip#set/video2.mp4"
        val receipts=listOf(saved("in-archive",own,"video1.mp4"),
            saved("foreign",other,"video2.mp4"),
            saved("archive",archive.url,"original.zip"))
        val result=CosplayTeleVideoCompleteness.audit(2,listOf(archive),
            receipts,setOf("in-archive","foreign","archive"))
        assertEquals(1,result.verifiedVideos)
        assertFalse(result.complete)
        assertTrue(CosplayTeleVideoCompleteness.audit(1,listOf(archive),
            receipts,setOf("in-archive","foreign","archive")).complete)
    }

    @Test fun fallbackRequiresExactSelectedUnfulfilledVideoReceipt(){
        val missing=CosplayTeleVideoCompleteness.Audit(2,0,false)
        assertTrue(CosplayTeleVideoCompleteness.queueVideoFallback(
            true,missing,false))
        assertFalse(CosplayTeleVideoCompleteness.queueVideoFallback(
            false,missing,false))
        // A publisher archive link is not evidence that its video bytes
        // were saved; allow the exact article's VideoFlow recovery.
        assertTrue(CosplayTeleVideoCompleteness.queueVideoFallback(
            true,missing,true))
        assertFalse(CosplayTeleVideoCompleteness.queueVideoFallback(
            true,CosplayTeleVideoCompleteness.Audit(2,2,true),true))
        assertFalse(CosplayTeleVideoCompleteness.queueVideoFallback(
            true,null,true))
        assertFalse(CosplayTeleVideoCompleteness.queueVideoFallback(
            true,CosplayTeleVideoCompleteness.Audit(0,0,true),true))
    }
    @Test fun discoveredVideoWithNoPublisherCountIsStillMandatory(){
        val video=media("selected-video",MediaKind.VIDEO,
            "https://cdn.example/selected.mp4")
        val before=CosplayTeleVideoCompleteness.audit(null,listOf(video),
            emptyList(),emptySet())
        assertEquals(1,before.expectedVideos)
        assertFalse(before.complete)
        val after=CosplayTeleVideoCompleteness.audit(null,listOf(video),
            listOf(saved("selected-video",video.url,"selected.mp4")),
            setOf("selected-video"))
        assertTrue(after.complete)
    }

    @Test fun underreportedVideoCountCannotHideAnotherSelectedOriginal(){
        val first=media("first",MediaKind.VIDEO,"https://cdn.example/one.mp4")
        val second=media("second",MediaKind.VIDEO,"https://cdn.example/two.mp4")
        val partial=CosplayTeleVideoCompleteness.audit(1,
            listOf(first,second),listOf(saved("first",first.url,"one.mp4")),
            setOf("first"))
        assertEquals(2,partial.expectedVideos)
        assertEquals(1,partial.verifiedVideos)
        assertFalse(partial.complete)
        val complete=CosplayTeleVideoCompleteness.audit(1,
            listOf(first,second),listOf(saved("first",first.url,"one.mp4"),
                saved("second",second.url,"two.mp4")),setOf("first","second"))
        assertTrue(complete.complete)
    }

    @Test fun oneArchiveOriginalCannotBeCountedTwice(){
        val archive=media("archive",MediaKind.ARCHIVE,
            "https://mediafire.com/file/official/selected.zip")
        val one="archive:"+archive.normalizedUrl+"#album/one.mp4"
        val receipt=saved("member-one",one,"one.mp4")
        val result=CosplayTeleVideoCompleteness.audit(2,listOf(archive),
            listOf(receipt,receipt),setOf("member-one"))
        assertEquals(1,result.verifiedVideos)
        assertFalse(result.complete)
    }

    @Test fun onlyExplicitOwnerTargetCanEnableSelectedAlbumFallback(){
        val policy=CosplayTeleVideoCompleteness
        assertTrue(policy.exactEntitySelection("album-1","album-1",
            "selected_album"))
        assertTrue(policy.exactEntitySelection("album-1","album-1",
            "gallery_only"))
        assertFalse(policy.exactEntitySelection(null,"album-1",
            "selected_album"))
        assertFalse(policy.exactEntitySelection("other","album-1",
            "selected_album"))
        assertFalse(policy.exactEntitySelection("site","site","site"))
    }

    @Test fun noAdvertisedCountDoesNotInventVideoRequirement(){
        val result=CosplayTeleVideoCompleteness.audit(null,emptyList(),
            emptyList(),emptySet())
        assertTrue(result.complete)
        assertEquals(0,result.expectedVideos)
    }
}
