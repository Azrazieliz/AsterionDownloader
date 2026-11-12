package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class VideoProviderRoutingTest {
    @Test fun localeAndMobileAliasesStayOnSameProvider() {
        assertTrue(VideoSiteProfiles.sameProvider(
            "https://fr.xvideos.com/","https://www.xvideos.com/video12345/example"))
        assertTrue(VideoSiteProfiles.sameProvider(
            "https://m.tnaflix.com/","https://www.tnaflix.com/video/example"))
        assertTrue(VideoSiteProfiles.sameProvider(
            "https://www.omg.xxx/fr/","https://omg.xxx/fr/videos/example"))
        assertFalse(VideoSiteProfiles.sameProvider(
            "https://www.xvideos.com/","https://xvideos.com.evil.example/video/123"))
        assertFalse(VideoSiteProfiles.sameProvider(
            "https://eporner.com/","https://another-provider.example/video"))
    }

    @Test fun specialistRouteRecognitionIsNotBasedOnAWebsiteHome() {
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://www.eporner.com/hd-porn/abc123/example"))
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://www.pornhub.com/view_video.php?viewkey=ph123456"))
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://fr.xvideos.com/video123456/example"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://www.xnxx.com/video-abc123/example"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://www.eporner.com/"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://www.xvideos.com/tags/example"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://pornhub.com/models/example"))
    }

    @Test fun javGuruPublishedNumericPostRoutesArePlayableCandidates(){
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://jav.guru/1076295/start-663-original-episode/"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://jav.guru/category/jav/"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://jav.guru/jav-actress-list/"))
    }
    @Test fun javideoActualReleaseIdRoutesAreVideoPages(){
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://javideo.net/en/fc2ppv-4994603"))
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://javideo.net/en/carib-111026-001"))
        assertFalse(VideoSiteProfiles.isVideoPage("https://javideo.net/en/"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://javideo.net/en/category/jav"))
    }
    @Test fun xvideosActualDotIdUrlsAreVideosNotCategoryPages(){
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://fr.xvideos.com/video.oupkhvt9b50/hsf-straight/0/example"))
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://www.xvideos.com/video123456/example"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://fr.xvideos.com/c/Cam_Porn-58"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://fr.xvideos.com/video.fake/"))
    }
    @Test fun failureClassificationsPreserveHttpMeaning(){
        assertEquals(VideoFailurePolicy.Kind.AUTH,
            VideoFailurePolicy.classify("Provider HTTP 403; account unavailable"))
        assertEquals(VideoFailurePolicy.Kind.RATE_LIMIT,
            VideoFailurePolicy.classify("HTTP error 429"))
        assertEquals(VideoFailurePolicy.Kind.GONE,
            VideoFailurePolicy.classify("HTTP Error 404: page not found"))
        assertEquals(VideoFailurePolicy.Kind.TRANSIENT,
            VideoFailurePolicy.classify("502 Bad Gateway"))
        assertEquals(VideoFailurePolicy.Kind.UNSUPPORTED,
            VideoFailurePolicy.classify("No suitable extractor for URL"))
    }
}
