package com.asterion.videoflow

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class ThreeDHentaiSignedOriginalTest {
    private val page="https://3dhentaix.com/filling-up-bellies-part-3/"
    private val media="https://cdn.3dhentaix.com/index.php?exp=1234567&sig=someSignature"

    @Test fun selectedFirstPartyAjaxNonceComesFromUniqueSelectedArticle(){
        val html="""<main><article id="post-7804"><div class="video-player">
          <video class="video-js" controls></video>
          <script>
          window.awpSignedPlayback = {"postId":7804,"nonce":"22d42ef016",
            "downloadTitle":"Filling Up Bellies &#8211; Part 3"};
          </script></div></article>
          <aside class="related"><video src="https://ad.example/teaser.mp4"></video></aside></main>"""
        val doc=Jsoup.parse(html,page)
        assertEquals(ThreeDHentaiSignedOriginal.Published(7804,"22d42ef016"),
            ThreeDHentaiSignedOriginal.publishedConfig(doc,page))
        assertTrue(ThreeDHentaiSignedOriginal.firstPartyFormat(page,media))
        assertTrue(NativeMediaDownloader.isNativeEligible(page,media,true))
        assertFalse(NativeMediaDownloader.isNativeEligible(page,media,false))
    }

    @Test fun rejectsRelatedWrongPostAdCdnAndSpoofedHost(){
        val selected="""<main><article id="post-7804"><div class="video-player">
            <script>window.awpSignedPlayback =
            {"postId":9999,"nonce":"abcdef1234"};</script></div></article></main>"""
        assertNull(ThreeDHentaiSignedOriginal.publishedConfig(
            Jsoup.parse(selected,page),page))
        listOf("https://cdn.3dhentaix.com.evil.invalid/index.php?sig=someSignature",
            "https://cdn.3dhentaix.com/preview.mp4?sig=someSignature",
            "https://cdn.3dhentaix.com/index.php",
            "https://ads.example/index.php?sig=someSignature",
            "http://cdn.3dhentaix.com/index.php?sig=someSignature")
            .forEach{assertFalse(it,ThreeDHentaiSignedOriginal.firstPartyFormat(page,it))}
        assertFalse(ThreeDHentaiSignedOriginal.firstPartyFormat(
            "https://rule34video.com/video/4727779/",media))
    }

    @Test fun rejectsMultipleConflictingPublisherConfigs(){
        val html="""<main><div class="video-player">
        <script>window.awpSignedPlayback = {"postId":7804,"nonce":"abcdef1234"};</script>
        <script>window.awpSignedPlayback = {"postId":7777,"nonce":"abcdef1234"};</script>
        </div></main>"""
        assertNull(ThreeDHentaiSignedOriginal.publishedConfig(
            Jsoup.parse(html,page),page))
    }
}
