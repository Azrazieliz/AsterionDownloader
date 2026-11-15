package com.asterion.videoflow

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class Mp4HentaiPublishedHostTest {
    private val selected="https://mp4hentai.com/2026/11/nagi-kannagi-18min50s-v/"

    @Test fun onlySelectedPublishedMirrorAndUpfilesLinksAreAccepted(){
        val html="""<article><div class="entry-content">
          <p>Format: mp4</p><p>Download Links: Uncensored</p>
          <a href="https://www.mirrored.to/files/DF7298C6/file.mp4_links">Mirror</a>
          <a href="https://upfiles.com/nGfW">Upfiles</a>
          <a href="https://ads.example/other.mp4">Download</a>
          <div class="related"><a href="https://upfiles.com/OTHER">Upfiles</a></div>
          </div></article>"""
        val result=Mp4HentaiPublishedHost.selectedLinks(
            Jsoup.parse(html,selected),selected)
        assertEquals(2,result.size)
        assertTrue(result.any{it.contains("mirrored.to/files/DF7298C6")})
        assertTrue(result.any{it=="https://upfiles.com/nGfW"})
        assertTrue(result.all{Mp4HentaiPublishedHost.isPublishedHost(selected,it)})
        val extracted=SiteSpecificOriginalSources.mp4hentai(
            Jsoup.parse(html,selected),selected).map{it.url}
        assertEquals(result,extracted)
    }

    @Test fun externalHostsAreNeverImplicitTrustedMedia(){
        assertFalse(Mp4HentaiPublishedHost.isPublishedHost(selected,
            "https://mirrored.to.evil.example/files/ABC/file"))
        assertFalse(Mp4HentaiPublishedHost.isPublishedHost(selected,
            "https://upfiles.com.evil.example/nGfW"))
        assertFalse(Mp4HentaiPublishedHost.isPublishedHost(selected,
            "http://upfiles.com/nGfW"))
        assertFalse(Mp4HentaiPublishedHost.isPublishedHost(selected,
            "https://127.0.0.1/file"))
        assertFalse(Mp4HentaiPublishedHost.isPublishedHost(
            "https://mp4hentai.com/", "https://upfiles.com/nGfW"))
        assertFalse(NativeMediaDownloader.isNativeEligible(selected,
            "https://www.mirrored.to/files/DF7298C6/file.mp4_links"))
    }

    @Test fun unrelatedPostAndRecommendationAnchorsCannotBecomeOriginals(){
        val html="""<div class="related"><a href="https://upfiles.com/OTHER">
            Upfiles</a></div><a href="https://upfiles.com/OTHER">Upfiles</a>"""
        assertTrue(Mp4HentaiPublishedHost.selectedLinks(
            Jsoup.parse(html,selected),selected).isEmpty())
    }
}
