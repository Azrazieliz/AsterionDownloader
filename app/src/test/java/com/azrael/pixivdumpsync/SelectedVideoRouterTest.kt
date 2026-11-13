package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class SelectedVideoRouterTest {
    @Test fun knownPublisherPagesGoToVideoFlowRegardlessOfIndexedKind(){
        assertTrue(SelectedVideoRouter.isSelectedVideo(
            "ADULT","https://javideo.net/en/fc2ppv-4994603"))
        assertTrue(SelectedVideoRouter.isSelectedVideo(
            "ADULT","https://helloporn.com/video/example"))
        assertTrue(SelectedVideoRouter.isSelectedVideo(
            "ADULT","https://xxxclub.to/videos/example"))
        assertTrue(SelectedVideoRouter.isSelectedVideo(
            "HENTAI","https://mp4hentai.com/2026/11/example-episode/"))
    }
    @Test fun genericGalleryAndNavigationPagesRemainInTheirOwnEngines(){
        assertFalse(SelectedVideoRouter.isSelectedVideo(
            "COSPLAY","https://javideo.net/en/fc2ppv-4994603"))
        assertFalse(SelectedVideoRouter.isSelectedVideo(
            "ADULT","https://javideo.net/en/category/jav"))
        assertFalse(SelectedVideoRouter.isSelectedVideo(
            "ADULT","https://unknown.example/gallery/123"))
    }
}
