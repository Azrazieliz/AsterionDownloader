package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class AlohaProviderSearchTest {
    @Test fun directSiteGetFormUsedWithoutCrossSiteQueryLeak(){
        val page="""<html><form action="/?sort=recent" method="GET">
          <input type="hidden" name="lang" value="en">
          <input type="search" name="s">
          </form></html>"""
        val url=KeywordSources.searchFormUrl(page,"https://example.org/","Hero One")
        assertNotNull(url)
        assertTrue(url!!.startsWith("https://example.org/?sort=recent&"))
        assertTrue(url.contains("s=Hero+One"))
        assertTrue(url.contains("lang=en"))
    }
    @Test fun unsafeSearchActionsAndPostFormsAreNotUsed(){
        assertNull(KeywordSources.searchFormUrl(
            """<form action="https://different.example/search">
            <input name="q"></form>""","https://example.org/","Hero"))
        assertNull(KeywordSources.searchFormUrl(
            """<form method="POST" action="/search">
            <input name="q"></form>""","https://example.org/","Hero"))
        assertNull(KeywordSources.searchFormUrl(
            """<form action="/search"><input name="email"></form>""",
            "https://example.org/","Hero"))
    }
}
