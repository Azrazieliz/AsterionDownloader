package com.azrael.pixivdumpsync

import com.asterion.videoflow.VideoSiteProfiles
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class PublisherCatalogCardRegressionTest {
    private val onscreens="https://www.onscreens.me/"
    private val uuid="b5b07ac8-57aa-44af-a6ff-6b2a227b2ec1"
    private val selected="https://www.onscreens.me/$uuid/sagnic-2026-11-20-cam4-recording"
    private val profile="https://www.onscreens.me/$uuid/sagnic-profile"
    private val taiav="https://taiav.com/cn/"
    private val movie="https://taiav.com/cn/movie/654424d67b410d2615db16e5"

    @Test fun onScreensUuidProfileIsNotRecordingEvenWithAnImage() {
        assertFalse(VideoSiteProfiles.isVideoPage(profile))
        assertFalse(AsterionAdultHub.publicMedia(
            profile,"sagnic profile","https://www.onscreens.me/covers/sagnic.webp"))
        assertFalse(KyoraCreatorPreviews.acceptedVideoCard(onscreens,profile))
        assertTrue(VideoSiteProfiles.isVideoPage(selected))
        assertTrue(KyoraCreatorPreviews.acceptedVideoCard(onscreens,selected))
    }

    @Test fun cachedOnScreensProfileNeverReappearsFromGenericNameOrCover() {
        val html="""<main><div class="video-card">
          <a href="$profile"><img src="/assets/profile.webp" alt="sagnic profile"></a>
          <a href="$selected"><img src="/assets/video.webp" alt="sagnic recording"></a>
          </div></main>"""
        val items=KyoraCreatorPreviews.parse(html,onscreens)
        assertEquals(listOf(selected),items.map{it.url})
    }

    @Test fun taiAvSiblingsSupplyRealPosterForExactMovieCard() {
        val html="""<main><div class="movie-card">
          <div class="hots-card uk-card">
            <div class="uk-card-media-top"><img src="/posters/real-movie.webp"></div>
            <a href="$movie">Studio recording</a>
          </div>
          </div></main>"""
        val cards=KyoraCreatorPreviews.parse(html,taiav)
        assertEquals(1,cards.size)
        assertEquals(movie,cards.single().url)
        assertEquals("https://taiav.com/posters/real-movie.webp",cards.single().cover)
        val entries=SourceIndexParser.entries(Jsoup.parse(html,taiav),
            taiav,"ADULT",12345L)
        assertEquals(1,entries.size)
        assertEquals("https://taiav.com/posters/real-movie.webp",
            entries.single().preview)
    }

    @Test fun taiAvCategoryAndProfilesDoNotBecomeFakeMovies() {
        val html="""<main><div class="movie-card">
          <img src="/posters/picture.webp">
          <a href="/cn/models/someone">Model profile</a>
          <a href="/cn/category/latest">Latest releases</a>
          </div></main>"""
        assertTrue(KyoraCreatorPreviews.parse(html,taiav).none{
            it.title.contains("profile",true)})
    }
}
