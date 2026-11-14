package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class ArchivebateSelectedMediaTest {
    @Test fun onlyPublisherOwnedSelectedRecordingWithRealPublishedPlayer(){
        val watch="https://archivebate.com/watch/16458050"
        assertTrue(ArchivebateSelectedMedia.isSelectedEmbed(watch,
            "https://mixdrop.ag/e/ABcdEF123"))
        assertFalse(ArchivebateSelectedMedia.isSelectedEmbed("https://archivebate.com/",
            "https://mixdrop.ag/e/ABcdEF123"))
        assertFalse(ArchivebateSelectedMedia.isSelectedEmbed(
            "https://archivebate.com/2257","https://mixdrop.ag/e/ABcdEF123"))
        assertFalse(ArchivebateSelectedMedia.isSelectedEmbed(
            watch,"https://ads.example/e/ABcdEF123"))
        assertFalse(ArchivebateSelectedMedia.isSelectedEmbed(
            watch,"https://mixdrop.ag/ads/click"))
        assertFalse(ArchivebateSelectedMedia.isSelectedEmbed(
            watch,"https://mixdrop.ag/e/ABcdEF123?redirect=advert"))
    }

    @Test fun selectedMediaCannotBeAnAdvertisementOrParentPagePreview(){
        assertTrue(ArchivebateSelectedMedia.isSelectedMedia(
            "https://cdn.freefile.io/path/real-original.mp4?token=private"))
        assertFalse(ArchivebateSelectedMedia.isSelectedMedia(
            "https://archivebate.com/wp-content/uploads/preview.mp4"))
        assertFalse(ArchivebateSelectedMedia.isSelectedMedia(
            "https://fakecdn.freefile.io/path/not-original.mp4"))
        assertFalse(ArchivebateSelectedMedia.isSelectedMedia(
            "https://cdn.freefile.io/redirect/advert?video=.mp4"))
        assertFalse(ArchivebateSelectedMedia.isSelectedMedia(
            "http://cdn.freefile.io/real.mp4"))
        assertFalse(ArchivebateSelectedMedia.isSelectedMedia(
            "https://cdn.freefile.io/real.m3u8"))
    }
}
