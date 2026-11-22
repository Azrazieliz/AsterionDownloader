package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class SearchRankingTest {
    @Test fun configuredProviderList() {
        val list=SearchRanking.parseProviders(
            "searxng|https://search.example|General\n"+
            "html|https://media.example/search?q={query}|Mirror\n"+
            "searxng|http://searcher.onion|Index")
        assertEquals(3,list.size)
        assertEquals(SearchProviderKind.HTML,list[1].kind)
    }
    @Test fun rejectedPublishersNeverReturnViaOpenWebIndex(){
        assertFalse(KeywordSources.siteDefinitions(VideoSearchArea.PORN)
            .any{it.contains("spankbang.com",true)})
        for(url in listOf(
            "https://spankbang.com/abc/video/name",
            "https://www.thisvid.com/videos/name/",
            "https://manyvids.com/video/12345",
            "https://spankbang.com.evil.example/watch/1"
        )){
            if(url.contains("evil.example"))assertTrue(
                KeywordSources.eligibleDiscoveryUrl(url))
            else assertFalse(KeywordSources.eligibleDiscoveryUrl(url))
        }
        assertTrue(KeywordSources.eligibleDiscoveryUrl(
            "https://porneec.com/video/example-video"))
        val web="""<li class="b_algo"><h2><a
            href="https://spankbang.com/abc/video/creator">Creator video</a></h2></li>
            <li class="b_algo"><h2><a
            href="https://porneec.com/video/creator">Creator video</a></h2></li>"""
        val hits=KeywordSources.parseWebIndex(web,
            "https://www.bing.com/search?q=creator","Bing Web")
        assertTrue(hits.any{it.url.contains("porneec.com")})
        assertFalse(hits.any{it.url.contains("spankbang.com")})
    }
    @Test fun categoriesKeepDedicatedSitesWhileGeneralWebStaysIndependent(){
        val categories=VideoSearchArea.values().toList()
        assertEquals(listOf(VideoSearchArea.HENTAI,VideoSearchArea.PORN,
            VideoSearchArea.PIRACY_DARKWEB,VideoSearchArea.GENERAL),categories)
        assertTrue(KeywordSources.siteDefinitions(VideoSearchArea.GENERAL)
            .isEmpty())
        val hentai=KeywordSources.siteDefinitions(VideoSearchArea.HENTAI)
        val porn=KeywordSources.siteDefinitions(VideoSearchArea.PORN)
        val piracy=KeywordSources.siteDefinitions(VideoSearchArea.PIRACY_DARKWEB)
        assertTrue(hentai.any{it.contains("hanime.tv")})
        assertTrue(porn.any{it.contains("pornhub.com")})
        assertTrue(piracy.any{it.contains("nyaa.si")})
        assertTrue(hentai.none{it in porn || it in piracy})
        assertTrue(porn.none{it in piracy})
    }
    @Test fun hentaiSpecificVideoPathsAndHanimeSearchResults(){
        val url="https://hanime.tv/hentai-videos/example-episode-01"
        assertTrue(SearchRanking.isVideoUrl(url))
        assertTrue(SearchRanking.isVideoUrl(
            "https://hentai.tv/episode/example-01/"))
        val json="""{"hits":"[{\"slug\":\"example-episode-01\",\"name\":\"Example Episode 01\"}]"}"""
        val results=KeywordSources.parseHanimeSearch(json)
        assertEquals(1,results.size)
        assertEquals(url,results.first().url)
        assertTrue(SearchRanking.deduplicate("example episode",results).isNotEmpty())
    }
    @Test fun namedTorrentIndexesAreSourcePagesNotVideos(){
        val sources=listOf(
            "https://sukebei.nyaa.si/view/1983012",
            "https://thepiratebay.org/description/12345",
            "https://nyaa.si/view/1917004",
            "https://1337x.to/torrent/9123456/example/")
        for(url in sources){
            assertTrue(url,SearchRanking.isIndexPage(url))
            assertFalse(url,SearchRanking.isVideoUrl(url))
        }
    }
    @Test fun providerNativeSelectedArticlesSurviveSearchVideoFiltering(){
        for(url in listOf(
            "https://jav.guru/1234567/owner-selected-original/",
            "https://javideo.net/en/fc2ppv-4994603",
            "https://uncensoredhentai.xxx/watch/selected-video/"
        )) assertTrue(url,SearchRanking.isVideoUrl(url))
        for(url in listOf(
            "https://jav.guru/category/new-videos/",
            "https://jav.guru.evil.example/1234567/owner-selected-original/",
            "https://javideo.net/en/",
            "https://helloporn.com/",
            "https://helloporn.com/video-123456-selected-original",
            "https://mp4hentai.com/2026/11/selected-file-post/",
            "https://hentaihaven.xxx/watch/example/episode-1/",
            "https://mp4hentai.com/2026/11/",
            "https://mp4hentai.com/2026/11/upcoming-hentai-release/"
        )) assertFalse(url,SearchRanking.isVideoUrl(url))
    }
    @Test fun indexedMediaIsNeverMistakenForDownloadableVideo(){
        val nyaa="https://nyaa.si/view/1917004"
        val torrent="https://1337x.to/torrent/9123456/video-name/"
        assertTrue(SearchRanking.isIndexPage(nyaa))
        assertTrue(SearchRanking.isIndexPage(torrent))
        assertFalse(SearchRanking.isVideoUrl(nyaa))
        assertFalse(SearchRanking.isVideoUrl(torrent))
        assertFalse(SearchRanking.isIndexPage("https://nyaa.si/?q=example"))
        assertTrue(SearchRanking.isVideoUrl(
            "https://hanime.tv/hentai-videos/example-title"))
    }
    @Test fun rejectsInternalAddressesAndMalformedTemplates() {
        assertFalse(SearchRanking.validUrl("http://127.0.0.1/test"))
        assertFalse(SearchRanking.validUrl("http://localhost/test"))
        assertTrue(SearchRanking.validUrl("https://media.example/post/12"))
        assertThrows(IllegalArgumentException::class.java){
            SearchRanking.parseProviders("html|https://media.example/search")
        }
    }
    @Test fun removesTrackingOnlyFromDeduplicationKey() {
        val original="https://www.example.org/watch/a?utm_source=test&id=5"
        val plain="https://example.org/watch/a?id=5"
        val result=SearchRanking.deduplicate("creator",listOf(
            RankedHit("creator video",original,"index"),
            RankedHit("other video",plain,"mirror")))
        assertEquals(1,result.size)
        assertEquals(original,result.first().url)
    }
    @Test fun rejectsIrrelevantResultsAndNonvideoPages() {
        val result=SearchRanking.deduplicate("rare phrase",listOf(
            RankedHit("Other title","https://example.com/watch/2","mirror"),
            RankedHit("Rare Phrase recording","https://example.com/watch/1","index")))
        assertEquals(1,result.size)
        assertTrue(result[0].title.contains("Rare Phrase"))
        assertFalse(SearchRanking.isVideoUrl("https://example.com/category/rare-phrase"))
        assertTrue(SearchRanking.isVideoUrl("https://www.pornhub.com/view_video.php?viewkey=abc"))
        assertFalse(SearchRanking.isVideoUrl("https://www.xvideos.com/video.oteflpmace7/anime_perverted_milf"))
        assertFalse(SearchRanking.isVideoUrl("https://www.pornhub.com/video/search?search=rare"))
    }
    @Test fun peerTubePublicSearchRetainsShortWatchLinksWithoutAdmittingListings() {
        val valid="https://video.example.org/w/123e4567-e89b-12d3-a456-426614174000"
        assertTrue(PeerTubeResultPolicy.isVideoWatch(valid))
        assertTrue(PeerTubeResultPolicy.isVideoWatch(
            "https://video.example.org/videos/watch/123e4567-e89b-12d3-a456-426614174000"))
        for(bad in listOf(
            "https://video.example.org/w/short",
            "https://video.example.org/w/",
            "https://video.example.org/videos",
            "http://video.example.org/w/123e4567-e89b-12d3-a456-426614174000",
            "https://127.0.0.1/w/123e4567-e89b-12d3-a456-426614174000",
            "https://video.example.org/w/123e4567-e89b-12d3-a456-426614174000?redirect=1"
        ))assertFalse(bad,PeerTubeResultPolicy.isVideoWatch(bad))
    }

}
