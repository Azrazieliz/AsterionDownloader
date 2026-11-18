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

    @Test fun actualMirrorHrefWithLiteralBracketsIsNotSilentlyDropped(){
        val html="""<article class="post"><div class="entry-content">
          <p>Download Links: Uncensored
            <a href="https://www.mirrored.to/files/DF7298C6/[ityppp]_Nagi_(Kannagi)_18min50S_V.mp4_links">Mirror</a>
            <a href="https://upfiles.com/nGfW">Upfiles</a>
          </p></div></article>"""
        val links=Mp4HentaiPublishedHost.selectedLinks(
            Jsoup.parse(html,selected),selected)
        assertEquals(2,links.size)
        assertTrue(links.any{it.contains("%5Bityppp%5D")})
        assertTrue(links.any{it=="https://upfiles.com/nGfW"})
        assertTrue(links.all{Mp4HentaiPublishedHost.isPublishedHost(selected,it)})
        assertNull(Mp4HentaiPublishedHost.publishedHref(
            selected,"https://invalid .test/[not-a-host]"))
    }

    @Test fun permittedPublisherRedirectRetainsSelectedHosterIdentity(){
        assertTrue(Mp4HentaiPublishedHost.isPublishedHost(selected,
            "https://upfilesgo.com/nGfW"))
        assertTrue(Mp4HentaiPublishedHost.isPublishedHost(selected,
            "https://www.mirrored.to/files/DF7298C6/file.mp4_links"))
        assertFalse(Mp4HentaiPublishedHost.isPublishedHost(selected,
            "https://example.net/file.mp4"))
        assertFalse(Mp4HentaiPublishedHost.isPublishedHost(selected,
            "https://upfilesgo.com/"))
    }

    @Test fun onlyClickedSelectedMirroredGetlinkCanNamePublicFileHosters(){
        val chosen="https://www.mirrored.to/files/DF7298C6/?dl=0"
        val link="https://www.mirrored.to/getlink?hid=ABC123&tid=DEF456"
        assertTrue(Mp4HentaiPublishedHost.selectedMirrorGetlink(chosen,link))
        assertFalse(Mp4HentaiPublishedHost.selectedMirrorGetlink(
            "https://evil.example/files/DF7298C6/",link))
        assertFalse(Mp4HentaiPublishedHost.selectedMirrorGetlink(chosen,
            "https://www.mirrored.to/getlink?hid=ABC123"))
        assertFalse(Mp4HentaiPublishedHost.selectedMirrorGetlink(chosen,
            "https://www.mirrored.to/getlink?hid=ABC123&tid=DEF456&advert=1"))
        assertFalse(Mp4HentaiPublishedHost.selectedMirrorGetlink(chosen,
            "https://www.mirrored.to.evil.example/getlink?hid=ABC123&tid=DEF456"))
        assertTrue(Mp4HentaiPublishedHost.selectedMirrorHandoff(link,
            "https://freedl.ink/syad3zdsqir0"))
        assertTrue(Mp4HentaiPublishedHost.selectedMirrorHandoff(link,
            "https://buzzheavier.com/tfa4kl9o9uo2"))
        assertTrue(Mp4HentaiPublishedHost.selectedMirrorHandoff(link,
            "https://rapidgator.net/file/abcdef123/download-name.mp4"))
        for(bad in listOf(
            "https://freedl.ink.evil.example/syad3zdsqir0",
            "https://buzzheavier.com/premium/OTHER",
            "https://rapidgator.net/file/OTHER/../ads",
            "https://ads.example/episode.mp4",
            "http://freedl.ink/syad3zdsqir0",
            "https://freedl.ink/syad3zdsqir0?download=other"
        )) assertFalse(bad,Mp4HentaiPublishedHost.selectedMirrorHandoff(link,bad))
        assertFalse(Mp4HentaiPublishedHost.selectedMirrorHandoff(
            chosen,"https://freedl.ink/syad3zdsqir0"))
    }

    @Test fun realUpfilesPublicCaptchaRequiresBrowserActionNotFakeDownload(){
        val selectedHost="https://upfilesgo.com/nGfW"
        val page=Jsoup.parse("""<html><head><title>Free download</title></head>
          <body><form id="file-captcha" action="/nGfW" method="post">
          <input name="_token" type="hidden" value="server-issued">
          <div class="g-recaptcha" data-sitekey="test-public"></div>
          </form></body></html>""",selectedHost)
        assertTrue(Mp4HentaiPublishedHost.requiresInteractiveVerification(page))
        assertEquals(VideoFailurePolicy.Kind.AUTH,
            VideoFailurePolicy.classify(
                "MP4Hentai selected file host requires CAPTCHA verification "+
                "in an authorized browser session"))
        val plain=Jsoup.parse("""<article><a href="/files/one">Get download links</a></article>""",
            selectedHost)
        assertFalse(Mp4HentaiPublishedHost.requiresInteractiveVerification(plain))
    }
    @Test fun repeatedNoVerifiedFileIsPermanentUnsupportedNotOther() {
        val kind=VideoFailurePolicy.classify(
            "mp4hentai.com: Video transfer: MP4Hentai host did not expose a verified download in five steps")
        assertEquals(VideoFailurePolicy.Kind.UNSUPPORTED,kind)
        assertEquals(0,VideoFailurePolicy.maxRetries(kind))
        assertEquals(VideoFailurePolicy.Kind.AUTH,VideoFailurePolicy.classify(
            "MP4Hentai selected file host requires CAPTCHA verification in an authorized browser session"))
    }
    @Test fun publicUpfilesFreeFormUsesOnlyServerIssuedSameHostToken() {
        val host="https://upfilesgo.com/nGfW"
        val page=Jsoup.parse("""<form id="free-download-form" method="post"
            action="/nGfW"><input type="hidden" name="_token"
            value="gFDcxkjUQnK5qVZ9xAB24FtJ"/>
            <input type="hidden" name="download_type" value="free"/>
            </form>""",host)
        val free=Mp4HentaiPublishedHost.publicFreeDownloadForm(page,host)
        assertNotNull(free)
        assertEquals(host,free!!.action)
        assertEquals("gFDcxkjUQnK5qVZ9xAB24FtJ",free.csrf)
        assertNull(Mp4HentaiPublishedHost.publicFreeDownloadForm(page,
            "https://upfilesgo.com/other"))
    }
    @Test fun publicUpfilesFreeFormRejectsCaptchaPaidTierAndExternalPosts() {
        val host="https://upfilesgo.com/nGfW"
        val forms=listOf(
            """<form id="free-download-form" method="post" action="https://evil.test/upload"><input type="hidden" name="_token" value="abcdefghijklmnop"/><input type="hidden" name="download_type" value="free"/></form>""",
            """<form id="free-download-form" method="post" action="/nGfW"><input type="hidden" name="_token" value="abcdefghijklmnop"/><input type="hidden" name="download_type" value="premium"/></form>""",
            """<form id="free-download-form" method="get" action="/nGfW"><input type="hidden" name="_token" value="abcdefghijklmnop"/><input type="hidden" name="download_type" value="free"/></form>""",
            """<form id="free-download-form" method="post" action="/nGfW"><input type="hidden" name="_token" value="abcdefghijklmnop"/><input type="hidden" name="download_type" value="free"/><input name="captcha" type="hidden"/></form>""",
            """<div class="cf-turnstile"></div><form id="free-download-form" method="post" action="/nGfW"><input type="hidden" name="_token" value="abcdefghijklmnop"/><input type="hidden" name="download_type" value="free"/></form>"""
        )
        for(html in forms)
            assertNull(html,Mp4HentaiPublishedHost.publicFreeDownloadForm(
                Jsoup.parse(html,host),host))
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
