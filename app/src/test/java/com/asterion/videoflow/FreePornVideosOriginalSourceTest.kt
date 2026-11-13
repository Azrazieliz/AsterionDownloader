package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class FreePornVideosOriginalSourceTest {
    private val page="https://www.freepornvideos.xxx/videos/93823084/serena-gomes-a-savage-debut-in-a-gangbang-with-double-anal/"
    @Test fun selectedOriginalAcceptsSamePublisherExtensionlessMedia(){
        assertTrue(FreePornVideosOriginalSource.selectedPage(page))
        assertTrue(FreePornVideosOriginalSource.firstPartyFormat(
            page,"https://www.freepornvideos.xxx/get_file?id=verified"))
        assertTrue(NativeMediaDownloader.isNativeEligible(
            page,"https://www.freepornvideos.xxx/get_file?id=verified",true))
    }
    @Test fun unrelatedWebsitesCannotUseExtensionlessNativeLane(){
        assertFalse(FreePornVideosOriginalSource.firstPartyFormat(
            page,"https://ads.bad.example/get_file"))
        assertFalse(FreePornVideosOriginalSource.firstPartyFormat(
            page,"http://www.freepornvideos.xxx/get_file"))
        assertFalse(FreePornVideosOriginalSource.firstPartyFormat(
            page,"https://www.freepornvideos.xxx/"))
        assertFalse(NativeMediaDownloader.isNativeEligible(
            page,"https://ads.bad.example/original",true))
        assertFalse(NativeMediaDownloader.isNativeEligible(
            page,"https://www.freepornvideos.xxx/get_file",false))
    }
}
