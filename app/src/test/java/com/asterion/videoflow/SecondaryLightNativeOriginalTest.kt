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
        for(url in listOf(
            "https://rule34video.com/",
            "https://rule34video.com/category/popular",
            "https://hentaibros.net/",
            "https://hentaibros.net/latest-videos",
            "https://helloporn.com/video-123456-selected-scene",
            "https://javideo.net/en/fc2ppv-4994603",
            "https://hentaihaven.xxx/watch/some-series-name",
            "https://rule34video.com.evil.example/video/123456",
            "javascript:alert(1)"
        )) assertFalse(url,SecondaryLightNativeOriginal.eligible(url))
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
