package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class ArchivebateSelectedMediaTest {
    @Test fun uploaderWatchRoutePreservesSameSelectedMixdropProvenance(){
        val watch="https://archivebate.com/u/watch/16458050"
        val embed="https://mixdrop.ag/e/z1p3lkkjhv17mj"
        assertTrue(ArchivebateSelectedMedia.isSelectedArticle(watch))
        assertTrue(ArchivebateSelectedMedia.isSelectedEmbed(watch,embed))
        assertFalse(ArchivebateSelectedMedia.isSelectedArticle(
            "https://archivebate.com/u/watch/16458050/other"))
        assertFalse(ArchivebateSelectedMedia.isSelectedEmbed(
            "https://archivebate.com/u/watch/not-a-number",embed))
    }

    @Test fun selectedWatchPageFindsPublishedMixdropIframeWithoutGenericPlayerCss(){
        val page="https://archivebate.com/watch/16458050"
        val player="https://mixdrop.ag/e/z1p3lkkjhv17mj"
        val html="""<main><section class="recording"><iframe
            src="$player" allowfullscreen></iframe>
            <aside><iframe src="https://ads.example/redirect"></iframe></aside>
            </section></main>"""
        val candidates=PublisherOriginalExtractors.fromHtml(page,html)
        assertEquals(listOf(player),candidates.map{it.url})
        assertEquals(page,candidates.single().referer)
    }

    @Test fun onlyPublisherOwnedSelectedRecordingWithRealPublishedPlayer(){
        val watch="https://archivebate.com/watch/16458050"
        assertTrue(ArchivebateSelectedMedia.isSelectedEmbed(watch,
            "https://mixdrop.ag/e/ABcdEF123"))
        assertFalse(ArchivebateSelectedMedia.isSelectedEmbed("https://archivebate.com/",
            "https://mixdrop.ag/e/ABcdEF123"))
        assertFalse(ArchivebateSelectedMedia.isSelectedEmbed(
            "https://archivebate.com/2257","https://mixdrop.ag/e/ABcdEF123"))
        assertFalse(ArchivebateSelectedMedia.isSelectedEmbed(
            watch,"https://ads.example/e/ABcdEF123"))
        assertFalse(ArchivebateSelectedMedia.isSelectedEmbed(
            watch,"https://mixdrop.ag/ads/click"))
        assertFalse(ArchivebateSelectedMedia.isSelectedEmbed(
            watch,"https://mixdrop.ag/e/ABcdEF123?redirect=advert"))
    }

    @Test fun realCanonicalPlayerRedirectIsAllowedButOtherMediaAndAdsAreBlocked(){
        val selected="https://mixdrop.ag/e/z1p3lkkjhv17mj"
        assertTrue(ArchivebateSelectedMedia.selectedRedirect(
            selected,"https://mxdrop.click/e/z1p3lkkjhv17mj"))
        assertTrue(ArchivebateSelectedMedia.selectedRedirect(selected,selected))
        assertFalse(ArchivebateSelectedMedia.selectedRedirect(
            selected,"https://mxdrop.click/e/OTHER12345678"))
        assertFalse(ArchivebateSelectedMedia.selectedRedirect(
            selected,"https://mxdrop.click/ads/redirect"))
        assertFalse(ArchivebateSelectedMedia.selectedRedirect(
            selected,"https://ads.example/e/z1p3lkkjhv17mj"))
        assertFalse(ArchivebateSelectedMedia.selectedRedirect(
            selected,"https://mxdrop.click/e/z1p3lkkjhv17mj?redirect=ad"))
    }

    @Test fun publisherPackedMdCoreWurlIsSelectedOriginalWithExactWatchReferrer(){
        val watch="https://archivebate.com/watch/16458050"
        val embed="https://mixdrop.ag/e/z1p3lkkjhv17mj"
        val canonical="https://mxdrop.click/e/z1p3lkkjhv17mj"
        val source="//gfve4dog1.mxcontent.net/path/selected-original.mp4?token=publisher"
        val js="""MDCore.poster="https://example.invalid/poster.jpg";
            MDCore.furl=" ";MDCore.wurl="$source";
            MDCore.vfile="selected-original.mp4";MDCore.vserver="gfve4dog1";
            MDCore.remotesub="";MDCore.referrer="https%3A%2F%2Farchivebate.com%2Fwatch%2F16458050";
            MDCore.blockAB=true;"""
        val accepted=ArchivebateSelectedMedia.fromDecoded(
            watch,embed,canonical,js)
        assertEquals("https://gfve4dog1.mxcontent.net/path/selected-original.mp4?token=publisher",
            accepted)
        assertTrue(ArchivebateSelectedMedia.isSelectedMedia(accepted!!))
        assertNull(ArchivebateSelectedMedia.fromDecoded(watch,embed,
            "https://mxdrop.click/e/someother12345",js))
        assertNull(ArchivebateSelectedMedia.fromDecoded(
            "https://archivebate.com/watch/999",embed,canonical,js))
        assertNull(ArchivebateSelectedMedia.fromDecoded(
            watch,embed,canonical,js.replace("mxcontent.net","ads.invalid")))
        assertNull(ArchivebateSelectedMedia.fromDecoded(
            watch,embed,canonical,js.replace("selected-original.mp4?token=publisher",
                "preview.mp4?token=publisher").replace("gfve4dog1.mxcontent.net",
                    "archivebate.com")))
        assertNull(ArchivebateSelectedMedia.fromDecoded(
            watch,embed,canonical,js.replace(
                "watch%2F16458050","watch%2F00000")))
    }
    @Test fun selectedMxcontentMp4AcceptsGenuineMediaButNotAdOrHttp(){
        assertTrue(ArchivebateSelectedMedia.isSelectedMedia(
            "https://gfve4dog1.mxcontent.net/path/movie.mp4?token=private"))
        assertFalse(ArchivebateSelectedMedia.isSelectedMedia(
            "https://mxcontent.net/path/movie.mp4"))
        assertFalse(ArchivebateSelectedMedia.isSelectedMedia(
            "https://ads.mxcontent.net.evil.example/path/movie.mp4"))
        assertFalse(ArchivebateSelectedMedia.isSelectedMedia(
            "http://gfve4dog1.mxcontent.net/path/movie.mp4"))
        assertFalse(ArchivebateSelectedMedia.isSelectedMedia(
            "https://archivebate.com/wp-content/preview.mp4"))
    }
    @Test fun selectedMediaCannotBeAnAdvertisementOrParentPagePreview(){
        assertTrue(ArchivebateSelectedMedia.isSelectedMedia(
            "https://cdn.freefile.io/path/real-original.mp4?token=private"))
        assertFalse(ArchivebateSelectedMedia.isSelectedMedia(
            "https://archivebate.com/wp-content/uploads/preview.mp4"))
        assertFalse(ArchivebateSelectedMedia.isSelectedMedia(
            "https://fakecdn.freefile.io/path/not-original.mp4"))
        assertFalse(ArchivebateSelectedMedia.isSelectedMedia(
            "https://cdn.freefile.io/redirect/advert?video=.mp4"))
        assertFalse(ArchivebateSelectedMedia.isSelectedMedia(
            "http://cdn.freefile.io/real.mp4"))
        assertFalse(ArchivebateSelectedMedia.isSelectedMedia(
            "https://cdn.freefile.io/real.m3u8"))
    }
}
