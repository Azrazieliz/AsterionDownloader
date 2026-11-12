package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class VerifiedSiteCatalogApiTest {
    @Test fun epornerOnlyAcceptsActualVideoPostsWithSourceOwnedCovers(){
        val json="""{"page":1,"total_pages":2,"videos":[
          {"url":"https://www.eporner.com/video-09wPiRnMhaU/example-scene/",
           "title":"Example original","default_thumb":{
              "src":"https://static-ca-cdn.eporner.com/thumbs/a.jpg"}},
          {"url":"https://www.eporner.com/category/all/",
           "title":"Category","default_thumb":{"src":"https://example.com/logo.jpg"}},
          {"url":"https://untrusted.example/video-100/foreign/",
           "title":"Foreign video","default_thumb":{"src":"https://example.com/a.jpg"}}
        ]}"""
        val result=VerifiedSiteCatalogApi.eporner(
            json,"https://www.eporner.com/","ADULT",123L)
        assertEquals(1,result.media.size)
        assertEquals("Example original",result.media.first().title)
        assertEquals("video",result.media.first().kind)
        assertEquals("https://static-ca-cdn.eporner.com/thumbs/a.jpg",
            result.media.first().preview)
        assertTrue(result.next!!.contains("page=2"))
    }

    @Test fun epornerHdPornPublishedPagesAreAdmittedFromOfficialCatalog(){
        val json="""{"page":1,"total_pages":1,"videos":[
          {"url":"https://www.eporner.com/hd-porn/ABCD123/video-title/",
           "title":"Publisher Original","default_thumb":{
              "src":"https://static-ca-cdn.eporner.com/thumbs/original.jpg"}},
          {"url":"https://www.eporner.com/categories/hardcore/",
           "title":"Category","default_thumb":{
              "src":"https://static-ca-cdn.eporner.com/thumbs/category.jpg"}}
        ]}"""
        val result=VerifiedSiteCatalogApi.eporner(
            json,"https://www.eporner.com/","ADULT",17L)
        assertEquals(1,result.media.size)
        assertEquals("https://www.eporner.com/video-ABCD123/",
            result.media.single().url)
    }

    @Test fun epornerSameVideoIdAliasesDoNotDownloadTwice(){
        val json="""{"page":1,"total_pages":1,"videos":[
          {"url":"https://www.eporner.com/video-ABCD123/title1/","title":"Original",
           "default_thumb":{"src":"https://static-ca-cdn.eporner.com/thumbs/1.jpg"}},
          {"url":"https://www.eporner.com/hd-porn/ABCD123/title2/","title":"Original HD",
           "default_thumb":{"src":"https://static-ca-cdn.eporner.com/thumbs/2.jpg"}}
        ]}"""
        val media=VerifiedSiteCatalogApi.eporner(
            json,"https://www.eporner.com/","ADULT").media
        assertEquals(1,media.size)
        assertEquals("https://www.eporner.com/video-ABCD123/",media.single().url)
    }
    @Test fun official3dHentaiPostsContainPublishedVideoAndPoster(){
        val json="""[{"status":"publish","type":"post",
          "link":"https://3dhentaix.com/gift-to-the-queen-act-3/",
          "title":{"rendered":"Gift To The Queen Act 3"},
          "yoast_head_json":{"og_image":[{"url":
             "https://3dhentaix.com/wp-content/uploads/posters/gift.webp"}]}},
          {"status":"publish","type":"post",
          "link":"https://3dhentaix.com/explore/categories/",
          "title":{"rendered":"Explore Categories"},
          "yoast_head_json":{"og_image":[{"url":
             "https://3dhentaix.com/logo.jpg"}]}}]"""
        val posts=VerifiedSiteCatalogApi.hentaiBros(
            json,"https://3dhentaix.com/","HENTAI",123L)
            .media.filter{com.asterion.videoflow.VideoSiteProfiles.isVideoPage(it.url)}
        assertEquals(1,posts.size)
        assertEquals("https://3dhentaix.com/gift-to-the-queen-act-3/",
            posts.first().url)
    }

    @Test fun hentaiBrosIndexesPublishedPostsNotNavigation(){
        val json="""[
          {"status":"publish","type":"post",
           "link":"https://hentaibros.net/example-animation-episode-2/",
           "title":{"rendered":"Example Animation Episode 2"},
           "yoast_head_json":{"og_image":[{"url":"https://hentaibros.net/media/episode.jpg"}]}},
          {"status":"draft","type":"post",
           "link":"https://hentaibros.net/draft-episode/",
           "title":{"rendered":"Draft Episode"},
           "yoast_head_json":{"og_image":[{"url":"https://hentaibros.net/a.jpg"}]}}
        ]"""
        val r=VerifiedSiteCatalogApi.hentaiBros(
            json,"https://hentaibros.net/","HENTAI")
        assertEquals(1,r.media.size)
        assertTrue(r.media.single().url.contains("episode-2"))
        assertFalse(r.media.single().queued)
    }

    @Test(expected = org.json.JSONException::class)
    fun invalidCatalogJsonIsNotSilentlyCountedAsSuccessful(){
        VerifiedSiteCatalogApi.eporner("<html>blocked</html>",
            "https://www.eporner.com/","ADULT")
    }
}
