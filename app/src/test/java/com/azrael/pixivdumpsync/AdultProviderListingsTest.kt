package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class AdultProviderListingsTest {
    @Test fun javGuruIncludesRealFirstPartyPostsNotCategoriesOrAds() {
        val html="""<main>
          <article><h2><a href="/1076811/mukc-135-genuine-publisher-post/">
            [MUKC-135] A public movie post</a></h2>
            <a href="https://foreign.example/tracker"><img src="/poster.jpg"></a></article>
          <nav><a href="/category/jav/">JAV</a></nav></main>"""
        val page=Jsoup.parse(html,"https://jav.guru/")
        val result=AdultProviderListings.entries(page,"https://jav.guru/","ADULT",1L)
        assertEquals(1,result.size)
        assertTrue(result.first().url.contains("/1076811/mukc-135"))
        assertEquals("video",result.first().kind)
    }
    @Test fun cumsMatchesObservedNumberAndSlugWhileIgnoringNavigation(){
        val html="""<div class="video-item">
           <a href="/video/240284/caryn-beaumont-hotel-massage-turns-sexual/">
           <img src="https://cdn.cums.net/a.jpg" alt="Caryn Beaumont Hotel Massage Turns Sexual"></a>
           <h3>Caryn Beaumont Hotel Massage Turns Sexual</h3></div>
           <nav><a href="/videos/">Videos</a>
           <a href="https://wrong.example/video/240284/fake/">Fake</a></nav>"""
        val result=AdultProviderListings.entries(
            Jsoup.parse(html,"https://cums.net/"),"https://cums.net/","ADULT",1L)
        assertEquals(1,result.size)
        assertTrue(result.first().title.contains("Caryn Beaumont"))
    }
    @Test fun archivebateAccessShellIsNotDeclaredVideoCatalog(){
        val html="""<h1>Explore the world's largest webcam archive</h1>
          <a href="/2257">Record keeping requirements</a>
          <a href="/?continue=true">Continue</a>
          <nav><a href="/tags/chaturbate">Chaturbate</a></nav>
          <h3>Recent Videos</h3>"""
        val result=SourceIndexParser.entries(Jsoup.parse(html,
            "https://archivebate.com/"),"https://archivebate.com/","ADULT",1L)
        assertTrue(result.isEmpty())
    }
    @Test fun actualArchivebatePostContinueLinksNeedNoArticleOrText(){
        // Actual public Chrome observation on 2026-11-13 yielded real
        // numeric watch links without any article/video-card wrappers.
        val html=(16458050 downTo 16458015).joinToString(""){
            "<a href=\"/watch/"+it+"\"><div class=\"thumb\">"+
                "<video poster=\"https://cdn.freefile.io/poster.jpg\"></video>"+
            "</div></a>"
        }
        val entries=AdultProviderListings.entries(
            Jsoup.parse(html,"https://archivebate.com/"),
            "https://archivebate.com/","ADULT",System.currentTimeMillis())
        assertEquals(36,entries.size)
        assertEquals("Archivebate recording #16458050",entries.first().title)
        assertEquals("https://archivebate.com/watch/16458050",
            entries.first().url)
        assertEquals("video",entries.first().kind)
    }

    @Test fun archivebateAdmitsOnlyActualFirstPartyWatchPages(){
        val html="""<article><h3><a href="/watch/12345">Published recording</a></h3>
          <img src="https://archivebate.com/cover.jpg"></article>
          <a href="/2257">Compliance statement</a>"""
        val result=AdultProviderListings.entries(Jsoup.parse(html,
            "https://archivebate.com/"),"https://archivebate.com/","ADULT",1L)
        assertEquals(1,result.size)
        assertEquals("https://archivebate.com/watch/12345",result.first().url)
    }
}
