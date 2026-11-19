package com.asterion.videoflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondaryLightNativeOriginalTest {
    @Test fun lightweightNativeAdmissionOnlyForExactSelectedPages(){
        assertTrue(SecondaryLightNativeOriginal.eligible(
            "https://rule34video.com/video/123456/selected-scene"))
        assertTrue(SecondaryLightNativeOriginal.eligible(
            "https://hentaibros.net/example-specific-video-episode/"))
        assertTrue(SecondaryLightNativeOriginal.eligible(
            "https://freepornvideos.xxx/videos/93823084/selected-scene/"))
        for(url in listOf(
            "https://rule34video.com/",
            "https://rule34video.com/category/popular",
            "https://hentaibros.net/",
            "https://hentaibros.net/latest-videos",
            "https://freepornvideos.xxx/",
            "https://freepornvideos.xxx/videos/latest",
            "https://freepornvideos.xxx.evil.example/videos/93823084/selected-scene",
            "https://helloporn.com/video-123456-selected-scene",
            "https://javideo.net/en/fc2ppv-4994603",
            "https://hentaihaven.xxx/watch/some-series-name",
            "https://rule34video.com.evil.example/video/123456",
            "javascript:alert(1)"
        )) assertFalse(url,SecondaryLightNativeOriginal.eligible(url))
    }
    @Test fun selectedFreePornVideosNativeLaneKeepsSameItemBinding(){
        val selected="https://freepornvideos.xxx/videos/93823084/selected-scene/"
        val direct="https://freepornvideos.xxx/get_file/8512/"+
            "355749bdf86771c3613065c71121e5ecc43ba820f5/"+
            "93823000/93823084/93823084_2160m.mp4/"
        assertTrue(SecondaryLightNativeOriginal.eligible(selected))
        assertTrue(SecondaryLightNativeOriginal.verifiedFirstPartyMp4(
            selected,direct))
        assertTrue(NativeMediaDownloader.isNativeEligible(
            selected,direct,true))
        assertFalse(NativeMediaDownloader.isNativeEligible(
            selected,direct,false))
        assertFalse(SecondaryLightNativeOriginal.verifiedFirstPartyMp4(
            selected,direct.replace("93823084_2160m","93823083_2160m")))
        assertFalse(SecondaryLightNativeOriginal.verifiedFirstPartyMp4(
            selected,"https://ads.example/other.mp4"))
    }

    @Test fun directFirstPartySelectedOriginalCanUseNativeWithoutSecondPython(){
        val selected="https://rule34video.com/video/123456/sample"
        val media="https://rule34video.com/get_file/some-id?download=true"
        assertTrue(Rule34VideoOriginalSource.firstPartyDownload(selected,media))
        assertTrue(NativeMediaDownloader.isNativeEligible(selected,media,true))
        assertFalse(NativeMediaDownloader.isNativeEligible(selected,
            "https://fake-provider.example/get_file/id?download=true",true))
    }
}
