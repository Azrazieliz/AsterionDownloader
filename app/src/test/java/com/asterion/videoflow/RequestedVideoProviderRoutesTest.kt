package com.asterion.videoflow

import com.azrael.pixivdumpsync.AlohaSources
import com.azrael.pixivdumpsync.ProviderRetirement
import org.junit.Assert.*
import org.junit.Test

class RequestedVideoProviderRoutesTest {
    @Test fun ownerRequestedProvidersRegisteredWithoutRetiredOnScreens(){
        val sites=AlohaSources.all.map{it.host}.toSet()
        assertTrue(sites.containsAll(setOf(
            "avple.tv","pimpbunny.com","en.xchina.co",
            "bubblecum.lol","pornavhd.com","camstreams.tv")))
        assertFalse("OnScreens must not reappear in catalog",
            "onscreens.me" in sites)
        assertTrue(ProviderRetirement.isRetired(
            "https://www.onscreens.me/profile"))
        assertFalse(ProviderRetirement.isRetired(
            "https://camstreams.tv/videos/1363858/example/"))
    }

    @Test fun genuineSelectedWatchRoutesAndListingsRemainDistinct(){
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://pimpbunny.com/videos/selected-scene-123/"))
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://en.xchina.co/video/6b00b2b6032b1"))
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://www.bubblecum.lol/video/selected-scene-example/"))
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://pornavhd.com/2026/11/14/selected_release_20261114/"))
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://camstreams.tv/videos/1363858/selected-recording/"))
        for(url in listOf("https://www.bubblecum.lol/models/",
            "https://camstreams.tv/models/","https://pimpbunny.com/categories/",
            "https://en.xchina.co/video-series/",
            "https://pornavhd.com/category/onlyfans/")){
            assertFalse("Not an original selected watch: "+url,
                VideoSiteProfiles.isVideoPage(url))
        }
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://www.onscreens.me/"))
    }

    @Test fun newSelectedPublisherParsersRequireOneExactPlayerMedia(){
        val selected=listOf(
            "https://pimpbunny.com/videos/selected-scene-123",
            "https://en.xchina.co/video/6b00b2b6032b1",
            "https://www.bubblecum.lol/video/selected-scene-example/",
            "https://pornavhd.com/2026/11/14/selected_20261114/",
            "https://camstreams.tv/videos/1363858/selected-recording/",
            "https://avple.tv/watch/selected-scene-123"
        )
        val player="""<main>
            <div class="video-player">
              <video src="https://media.example.net/selected-full.mp4"
                data-duration="1800"></video>
            </div>
            <aside class="recommended">
              <video src="https://media.example.net/unrelated.mp4"></video>
            </aside></main>"""
        for(page in selected){
            assertEquals(page,listOf("https://media.example.net/selected-full.mp4"),
                PublisherOriginalExtractors.fromHtml(page,player).map{it.url})
        }
    }

    @Test fun newPublishersRejectUnattributedJsonLdAndGlobalMediaMetadata(){
        val selected=listOf(
            "https://pimpbunny.com/videos/selected-scene-123",
            "https://en.xchina.co/video/6b00b2b6032b1",
            "https://www.bubblecum.lol/video/selected-scene-example/",
            "https://pornavhd.com/2026/11/14/selected_20261114/",
            "https://camstreams.tv/videos/1363858/selected-recording/",
            "https://avple.tv/watch/selected-scene-123")
        val unrelatedMeta="""<head>
          <script type="application/ld+json">{
            "@type":"VideoObject",
            "name":"Suggested video",
            "contentUrl":"https://media.example.net/unrelated-full.mp4"
          }</script>
          <meta itemprop="contentUrl"
            content="https://media.example.net/unrelated-recommendation.mp4">
          </head>"""
        for(page in selected){
            assertTrue("Never download a recommendation as the selected video: "+page,
                PublisherOriginalExtractors.fromHtml(page,
                    unrelatedMeta+"<main><aside class='recommended'>"+
                    "<video src='https://media.example.net/sidebar.mp4'>"+
                    "</video></aside></main>").isEmpty())
            assertEquals(listOf("https://media.example.net/selected-full.mp4"),
                PublisherOriginalExtractors.fromHtml(page,
                    unrelatedMeta+"<main><div class='video-player'>"+
                    "<video src='https://media.example.net/selected-full.mp4'>"+
                    "</video></div></main>").map{it.url})
        }
    }

    @Test fun providerHtmlParsingCannotTreatAProfileOrThumbnailAsOriginal(){
        val selected="https://www.bubblecum.lol/video/selected-scene-example/"
        assertTrue(PublisherOriginalExtractors.owns(selected))
        val html="""<main><aside class="recommended">
          <video src="https://cdn.example.com/advertisement.mp4"></video>
          </aside><div class="video-player">
          <video poster="https://cdn.example.com/preview.jpg"></video>
          </div></main>"""
        assertTrue(PublisherOriginalExtractors.fromHtml(selected,html).isEmpty())
    }
}
