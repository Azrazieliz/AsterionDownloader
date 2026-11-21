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

    @Test fun noAdvertisedCountDoesNotInventVideoRequirement(){
        val result=CosplayTeleVideoCompleteness.audit(null,emptyList(),
            emptyList(),emptySet())
        assertTrue(result.complete)
        assertEquals(0,result.expectedVideos)
    }
}
