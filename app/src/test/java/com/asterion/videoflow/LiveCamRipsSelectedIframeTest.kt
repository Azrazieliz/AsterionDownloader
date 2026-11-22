package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

/** Selected first-party iframe evidence, never an unrelated page iframe. */
class LiveCamRipsSelectedIframeTest {
    private val selected="https://livecamrips.to/video/1234567/"
    @Test fun publisherSelectedVidaraPlayerCanReachTransferResolver(){
        val html="""
          <html><body><main><div class="video-player">
            <iframe id="iframe-video"
              src="https://vidara.to/Abc123Def456"></iframe>
          </div></main>
          <aside><iframe src="https://other.example/ads"></iframe></aside>
          </body></html>
        """
        val result=PublisherOriginalExtractors.fromHtml(selected,html)
        assertEquals(1,result.size)
        assertEquals("https://vidara.to/Abc123Def456",result.single().url)
    }
    @Test fun realBlockedRecordingPlayerIsNotMistakenForAnUnsupportedVideo(){
        for(host in listOf("vidara.to","video.playerrecord.cfd")){
            val blocked=SiteSpecificOriginalSources.selectedRecordingHostBlocker(
                host,403,false)
            assertNotNull(blocked)
            assertEquals(VideoFailurePolicy.Kind.AUTH,
                VideoFailurePolicy.classify(blocked!!))
            assertNotNull(SiteSpecificOriginalSources.selectedRecordingHostBlocker(
                host,429,false))
            assertNotNull(SiteSpecificOriginalSources.selectedRecordingHostBlocker(
                host,null,true))
            assertNull(SiteSpecificOriginalSources.selectedRecordingHostBlocker(
                host,200,false))
        }
        assertNull(SiteSpecificOriginalSources.selectedRecordingHostBlocker(
            "ads.example",403,false))
    }
    @Test fun unrelatedAdsAndLookalikeHostsMustNotBeSelected(){
        val html="""
          <main><div id="player">
            <iframe id="iframe-video"
              src="https://vidara.to.evil.example/Abc123Def456"></iframe>
          </div><aside><iframe id="iframe-video"
              src="https://vidara.to/Abc123Def456"></iframe></aside></main>
        """
        assertTrue(PublisherOriginalExtractors.fromHtml(selected,html).isEmpty())
    }
}
