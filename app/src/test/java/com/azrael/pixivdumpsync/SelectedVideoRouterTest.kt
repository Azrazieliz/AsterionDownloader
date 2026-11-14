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
    @Test fun mislabeledKnownPlayerStillUsesVideoEngine(){
        assertTrue(SelectedVideoRouter.isSelectedVideo(
            "HENTAI","https://hentaibros.net/deco-x-deco-the-animation-episode-1/","image"))
        assertTrue(SelectedVideoRouter.isSelectedVideo(
            "ADULT","https://rule34video.com/video/4727221/","image"))
        assertFalse(SelectedVideoRouter.isSelectedVideo(
            "ADULT","https://helloporn.com/","video"))
        assertFalse(SelectedVideoRouter.isSelectedVideo(
            "ADULT","https://helloporn.com/categories/latest","video"))
        assertFalse(SelectedVideoRouter.isSelectedVideo(
            "ADULT","https://unknown.example/video/123","video"))
    }
    @Test fun genericGalleryAndNavigationPagesRemainInTheirOwnEngines(){
        assertFalse(SelectedVideoRouter.isSelectedVideo(
            "COSPLAY","https://javideo.net/en/fc2ppv-4994603"))
        assertFalse(SelectedVideoRouter.isSelectedVideo(
            "ADULT","https://javideo.net/en/category/jav"))
        assertFalse(SelectedVideoRouter.isSelectedVideo(
            "ADULT","https://unknown.example/gallery/123"))
        assertTrue(SelectedVideoRouter.isSelectedVideo(
            "ADULT","https://javideo.net/current-unknown-slug","video"))
        assertTrue(SelectedVideoRouter.isSelectedVideo(
            "ADULT","https://helloporn.com/current-unknown-slug","video"))
        assertFalse(SelectedVideoRouter.isSelectedVideo(
            "COSPLAY","https://helloporn.com/current-unknown-slug","video"))
    }
}
