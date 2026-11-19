package com.azrael.pixivdumpsync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadOverviewPolicyTest {
    @Test fun activeNonImageOriginalsAreVisible() {
        for(pair in listOf(
            "Video" to "publisher-original.mp4",
            "Comic ZIP" to "chapter-42.cbz",
            "EPUB" to "novel.epub",
            "Gallery" to "complete-album.zip",
            "Games" to "game.7z",
            "Assets" to "model.glb"
        )){
            assertTrue(pair.first+" "+pair.second,
                DownloadOverviewPolicy.isLiveFile("ACTIVE",
                    pair.first,pair.second,"*/*"))
        }
    }
    @Test fun imageMediaAndArtworkModulesRemainAggregateOnly() {
        for(pair in listOf(
            "Pixiv" to "illust_12_p0.png",
            "X" to "photo.webp",
            "Gallery" to "cosplay_37_2.jpg",
            "Images" to "original.avif",
            "Cosplay" to "thumbnail"
        )){
            assertFalse(pair.first+" "+pair.second,
                DownloadOverviewPolicy.isLiveFile("ACTIVE",
                    pair.first,pair.second,"image/*"))
        }
        assertFalse(DownloadOverviewPolicy.isLiveFile("ACTIVE",
            "Gallery","raw-picture.jpeg","*/*"))
        assertFalse(DownloadOverviewPolicy.isLiveFile("ACTIVE",
            "Pixiv","artwork-without-extension","*/*"))
    }
    @Test fun onlyCurrentlyActiveFilesAreDetailed() {
        for(state in listOf("DONE","FAILED","HELD","PAUSED","QUEUED")){
            assertFalse(DownloadOverviewPolicy.isLiveFile(
                state,"Video","real.mp4","video/mp4"))
        }
        assertTrue(DownloadOverviewPolicy.isLiveFile(
            "ACTIVE","Video","real.mp4","video/mp4"))
    }
    @Test fun missingActualSavedVideoCountsAsFailureRatherThanCompleted(){
        val oneMissing=DownloadOverviewPolicy.reconciledVideoCounts(
            done=1,failed=0,missingOriginals=1)
        org.junit.Assert.assertEquals(0,oneMissing.saved)
        org.junit.Assert.assertEquals(1,oneMissing.failed)
        val large=DownloadOverviewPolicy.reconciledVideoCounts(
            done=120,failed=7,missingOriginals=3)
        org.junit.Assert.assertEquals(117,large.saved)
        org.junit.Assert.assertEquals(10,large.failed)
        val allReadable=DownloadOverviewPolicy.reconciledVideoCounts(
            done=8,failed=2,missingOriginals=0)
        org.junit.Assert.assertEquals(8,allReadable.saved)
        org.junit.Assert.assertEquals(2,allReadable.failed)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java){
            DownloadOverviewPolicy.reconciledVideoCounts(1,0,2)
        }
    }

    @Test fun completionPercentRequiresRealFragmentDenominator() {
        org.junit.Assert.assertEquals(0,
            DownloadOverviewPolicy.hlsCompletion("0/99 fragments"))
        org.junit.Assert.assertEquals(49,
            DownloadOverviewPolicy.hlsCompletion("49/100 fragments"))
        org.junit.Assert.assertEquals(95,
            DownloadOverviewPolicy.hlsCompletion("4/4 fragments"))
        org.junit.Assert.assertEquals(95,
            DownloadOverviewPolicy.hlsCompletion("751/751 fragments"))
        org.junit.Assert.assertNull(
            DownloadOverviewPolicy.hlsCompletion("1% engine stage"))
        org.junit.Assert.assertNull(
            DownloadOverviewPolicy.hlsCompletion("101/100 fragments"))
        org.junit.Assert.assertNull(
            DownloadOverviewPolicy.hlsCompletion("10/0 fragments"))
        org.junit.Assert.assertNull(
            DownloadOverviewPolicy.hlsCompletion(null))
    }

    @Test fun misleadingFileNameCannotOverrideImageMime() {
        assertFalse(DownloadOverviewPolicy.isLiveFile(
            "ACTIVE","Video","download.mp4","image/png"))
        assertFalse(DownloadOverviewPolicy.isLiveFile(
            "ACTIVE","Games","preview.jpg","application/octet-stream"))
        assertTrue(DownloadOverviewPolicy.isLiveFile(
            "ACTIVE","Gallery","full-album.zip","application/zip"))
    }
}
