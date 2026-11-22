package com.azrael.pixivdumpsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class PublisherSearchPosterTest {
    @Test fun firstPartyOgImageIsResolvedFromSelectedVideoPage(){
        val html="<html><head><meta property=\"og:image\" "+
            "content=\"/uploads/poster.jpg\"></head></html>"
        assertEquals("https://example.org/uploads/poster.jpg",
            PublisherSearchPoster.fromHtml(html,"https://example.org/video/123"))
    }
    @Test fun selectedBrowserPlayerSnapshotRetainsActualPublisherPoster(){
        val variants=listOf(
            "<html><head><meta property='og:image' "+
                "content='https://cdn.example.org/poster.webp'></head>"+
                "<body><main><div id='kt_player'></div></main></body></html>",
            "<html><head><meta property='og:image' "+
                "content='https://cdn.example.org/poster.webp'></head>"+
                "<body><main><div id='video-player' "+
                "data-src='https://example.org/hls/id/master.m3u8'></div>"+
                "</main></body></html>",
            "<html><head><meta property='og:image' "+
                "content='https://cdn.example.org/poster.webp'></head>"+
                "<body><article id='post-123'></article></body></html>"
        )
        for(html in variants)assertEquals(
            "https://cdn.example.org/poster.webp",
            PublisherSearchPoster.fromHtml(html,
                "https://example.org/videos/selected-work"))
    }
    @Test fun browserMetadataDoesNotTurnVerificationPageIntoPoster(){
        assertNull(PublisherSearchPoster.fromHtml(
            "<html><body><h1>Verify you are human</h1></body></html>",
            "https://example.org/videos/selected-work"))
        assertNull(PublisherSearchPoster.fromHtml(
            "<html><head><meta property='og:image' "+
                "content='https://bad.example/logo.svg'></head></html>",
            "https://example.org/videos/selected-work"))
    }

    @Test fun javascriptAndLocalPosterAddressesAreRejected(){
        val html="<meta property=\"og:image\" content=\"javascript:alert(1)\">"
        assertNull(PublisherSearchPoster.fromHtml(html,
            "https://example.org/video/123"))
        val privateHost="<meta property=\"og:image\" "+
            "content=\"http://127.0.0.1/a.jpg\">"
        assertNull(PublisherSearchPoster.fromHtml(privateHost,
            "https://example.org/video/123"))
    }
    @Test fun webSearchParsesActualPublisherResultsNotSearchPages(){
        val html="""<div class="algo-sr"><div class="compTitle"><h3>
              <a href="https://example.org/video/123">Example Performer Scene</a>
              </h3></div><div class="compText">Actual video page</div></div>
              <div class="algo-sr"><div class="compTitle"><h3>
              <a href="https://example.org/search?q=example">Search</a>
              </h3></div></div>"""
        val found=KeywordSources.parseWebIndex(html,
            "https://search.yahoo.com/search?p=example","Yahoo Web")
        assertEquals(1,found.size)
        assertEquals("https://example.org/video/123",found.first().url)
        assertTrue(found.first().description.contains("Actual"))
    }}
