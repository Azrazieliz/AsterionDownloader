package com.azrael.pixivdumpsync

import com.asterion.videoflow.VideoSiteProfiles
import org.junit.Assert.*
import org.junit.Test

/** Preserve the owner-approved Aloha providers. Retired sources retain data. A provider must be routed
 * through a real content engine, not silently downgraded to an inert bookmark.
 * This is a routing test, NOT proof of live-site historical completeness.
 */
class ApprovedProviderCoverageTest {
    @Test fun allOwnerRetainedSitesHaveASyncEngine(){
        val sites=AlohaSources.all
        assertEquals(37,sites.size)
        val missing=sites.filter{site->
            var host=site.host.lowercase()
            for(prefix in listOf("m.","fr.","en.")){
                if(host.startsWith(prefix))host=host.removePrefix(prefix)
            }
            val routed=when(site.area){
                AlohaArea.COSPLAY ->
                    host in setOf("4khd.com","buondua.com","kiutaku.com",
                        "cosplaytele.com","notfans.com") ||
                    SiteBackfillProfiles.supports(host)
                AlohaArea.ADULT,AlohaArea.HENTAI ->
                    host in VideoSiteProfiles.coveredHosts
                AlohaArea.VIDEOS -> host=="onejav.com"
                AlohaArea.BOOKS ->
                    host=="mp4directs.com" || SiteBackfillProfiles.supports(host)
                AlohaArea.COMICS -> SiteBackfillProfiles.supports(host)
                AlohaArea.GAMES -> host=="sukebei.nyaa.si"
                AlohaArea.ASSETS -> host=="db.bepis.moe"
                else->false
            }
            !routed
        }
        assertTrue("Providers lacking a dedicated/native route: "+
            missing.map{it.name+" ("+it.host+")"},missing.isEmpty())
    }
    @Test fun textSeriesNeverProduceComicZip(){
        for(url in listOf("https://freewebnovel.com/novel/i-am-the-fated-villain",
            "https://novelbuddy.com/novel/example",
            "https://readnovelfull.com/book/example")){
            assertTrue(NovelEpubParser.supportedSeries(url))
            assertFalse(NovelEpubParser.readableZipSeries(url))
        }
        assertTrue(NhentaiChapters.isGallery("https://nhentai.net/g/12345"))
        assertTrue(SushiScanChapters.isSeries(
            "https://sushiscan.net/catalogue/example/"))
    }
}
