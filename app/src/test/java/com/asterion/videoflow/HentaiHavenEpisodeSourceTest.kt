package com.asterion.videoflow

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/** Offline identity and no-preview contracts, not publisher download receipts. */
class HentaiHavenEpisodeSourceTest {
    private val series="https://hentaihaven.xxx/watch/sample-selected-series"
    private val chosen=series+"/episode-2"
    private val master="https://octopusmanifest.org/selected-video-abc123/master.m3u8"

    @Test fun selectedSeriesIsNotAnEpisodeAndOnlyItsOwnEpisodeLinksAreListed(){
        val document=Jsoup.parse("""<main>
            <a href="/watch/sample-selected-series/episode-1">First</a>
            <a href="/watch/sample-selected-series/episode-2">Second</a>
            <a href="/watch/unrelated-series/episode-1">Different series</a>
            <aside class="related"><a href="/watch/sample-selected-series/episode-3">
                Recommendation</a></aside>
            </main>""",series)
        assertTrue(HentaiHavenEpisodeSource.isSeries(series))
        assertFalse(HentaiHavenEpisodeSource.isEpisode(series))
        assertTrue(HentaiHavenEpisodeSource.isEpisode(chosen))
        assertEquals(listOf("$series/episode-1",chosen),
            HentaiHavenEpisodeSource.episodes(document,series))
    }

    @Test fun exactVideoObjectContentUrlWinsOverNextJsRecommendationPreviews(){
        val page="""<main><video src="https://preview.invalid/unrelated.mp4"></video>
            <script type="application/ld+json">{
                "@context":"https://schema.org","@graph":[
                {"@type":"VideoObject","url":"$series/episode-1",
                 "contentUrl":"https://octopusmanifest.org/wrong-scene/master.m3u8"},
                {"@type":"VideoObject","url":"$chosen",
                 "contentUrl":"$master"}]}</script></main>"""
        assertEquals(master,HentaiHavenEpisodeSource.publishedHls(
            Jsoup.parse(page,chosen),chosen))
    }

    @Test fun otherEpisodeOrUnapprovedHlsCannotBecomeSelectedOriginal(){
        val mismatch="""<script type="application/ld+json">{
            "@type":"VideoObject","url":"$series/episode-3",
            "contentUrl":"$master"}</script>"""
        assertNull(HentaiHavenEpisodeSource.publishedHls(
            Jsoup.parse(mismatch,chosen),chosen))
        val hostile="""<script type="application/ld+json">{
            "@type":"VideoObject","url":"$chosen",
            "contentUrl":"https://octopusmanifest.org.evil.example/fake/master.m3u8"}
            </script>"""
        assertNull(HentaiHavenEpisodeSource.publishedHls(
            Jsoup.parse(hostile,chosen),chosen))
        assertFalse(HentaiHavenEpisodeSource.isApprovedHls(
            "https://octopusmanifest.org/fake/master.m3u8?other=1"))
        assertFalse(HentaiHavenEpisodeSource.isEpisode(
            "https://hentaihaven.xxx/watch/sample-selected-series"))
    }

    @Test fun conflictingSelectedHlsUrlsFailClosed(){
        val html="""<script type="application/ld+json">[
            {"@type":"VideoObject","url":"$chosen",
              "contentUrl":"$master"},
            {"@type":"VideoObject","url":"$chosen",
              "contentUrl":"https://octopusmanifest.org/different-video/master.m3u8"}
            ]</script>"""
        assertNull(HentaiHavenEpisodeSource.publishedHls(
            Jsoup.parse(html,chosen),chosen))
    }
}
