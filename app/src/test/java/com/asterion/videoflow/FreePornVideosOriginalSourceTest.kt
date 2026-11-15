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
    @Test fun liveSelectedHtml5SourceDoesNotNeedPythonExtractor(){
        val page=this.page
        val best="https://www.freepornvideos.xxx/get_file/8512/"+
            "355749bdf86771c3613065c71121e5ecc43ba820f5/"+
            "93823000/93823084/93823084_2160m.mp4/"
        val lower=best.replace("2160m","720m")
        val other=best.replace("93823084_2160m","93823083_2160m")
        val html="""<main><video controls>
            <source src="$lower" type="video/mp4" label="720p">
            <source src="$best" type="video/mp4" label="2160p">
            <source src="$other" type="video/mp4" label="unrelated scene">
        </video><aside><video src="https://ads.test/teaser.mp4"></video></aside></main>"""
        val doc=org.jsoup.Jsoup.parse(html,page)
        assertEquals(best,FreePornVideosOriginalSource.selectedOriginal(doc,page))
        assertEquals(2160,FreePornVideosOriginalSource.selectedFileQuality(page,best))
        assertNull(FreePornVideosOriginalSource.selectedFileQuality(page,other))
        assertNull(FreePornVideosOriginalSource.selectedOriginal(
            org.jsoup.Jsoup.parse("<video src='$other'></video>",page),page))
    }
    @Test fun directSelectedFileMustNotAllowAnAdOrDifferentScene(){
        assertNull(FreePornVideosOriginalSource.selectedFileQuality(page,
            "https://www.freepornvideos.xxx/get_file/123/test_2160m.mp4/"))
        assertNull(FreePornVideosOriginalSource.selectedFileQuality(page,
            "https://www.freepornvideos.xxx/videos/93823084/some-article/"))
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
            page,"https://ads.bad.example/original.mp4",true))
        assertFalse(NativeMediaDownloader.isNativeEligible(
            page,"https://www.freepornvideos.xxx/get_file",false))
    }
}
