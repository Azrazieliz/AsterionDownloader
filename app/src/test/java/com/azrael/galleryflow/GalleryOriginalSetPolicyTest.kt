package com.azrael.galleryflow

import org.junit.Assert.*
import org.junit.Test

class GalleryOriginalSetPolicyTest {
    @Test fun selectedArchivesKeepTheirOwnMembersAcrossRescans(){
        val source=SourceId.COSPLAYTELE
        val url="https://example.test/archive.zip"
        val archive=MediaRef(source,"archive","album",0,url,url,url,kind=MediaKind.ARCHIVE)
        fun row(id:String,owner:String,path:String)=MediaRecord(source,id,owner,"site",0,
            path,"content://media/1","photo.jpg",TransferState.COMPLETE,null,null,null)
        val own=row("own","album","archive:"+url+"#photo.jpg")
        val foreign=row("foreign","other","archive:"+url+"#photo.jpg")
        val stale=row("stale","album","https://example.test/related.jpg")
        assertEquals(setOf("archive","own"),
            GalleryOriginalSetPolicy.retainedIds(listOf(archive),listOf(own,foreign,stale)))
        val counted=archive.copy(expectedArchivePhotos=1)
        assertFalse(GalleryOriginalSetPolicy.archiveNeedsRecovery(
            counted,listOf(own),setOf("own")))
        assertTrue(GalleryOriginalSetPolicy.archiveNeedsRecovery(
            counted,listOf(own),emptySet()))
        assertTrue(GalleryOriginalSetPolicy.archiveNeedsRecovery(
            counted,listOf(foreign),setOf("foreign")))
    }

    @Test fun unrelatedHistoricRetryRowsMustNotStarveCorrectSelectedOriginals(){
        val requested=setOf("selected-photo-01","selected-photo-02")
        val valid=setOf("selected-photo-01","selected-photo-02")
        assertTrue(GalleryOriginalSetPolicy.complete(requested,valid))
    }
    @Test fun missingOrZeroByteRequestedOriginalNeverPasses(){
        assertFalse(GalleryOriginalSetPolicy.complete(
            setOf("selected-photo-01","selected-photo-02"),
            setOf("selected-photo-01")))
        assertFalse(GalleryOriginalSetPolicy.complete(emptySet(),setOf("stale-photo")))
        assertFalse(GalleryOriginalSetPolicy.complete(
            setOf("selected-photo-02"),setOf("stale-photo")))
    }
    @Test fun deduplicatedVerifiedFileCanStillCompleteItsOwnOriginal(){
        assertTrue(GalleryOriginalSetPolicy.complete(
            setOf("a","b"),setOf("a","b","historic-unrelated")))
    }
}
