package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

/** Regression for comparing a full provider URL against a bare host name. */
class SiteRunControlTest {
    private fun source(url:String,area:String="ADULT")=
        IndexedSource(url,url,area,true,null,0,null,0,"generic")

    @Test fun exactUrlWinsOverSiblingsOnSameProvider(){
        val sources=listOf(
            source("https://example.org/creator/first"),
            source("https://example.org/creator/second"))
        val actual=SiteRunControl.matchingSource(
            "https://example.org/creator/second/", "ADULT", sources)
        assertEquals("https://example.org/creator/second",actual?.url)
    }
    @Test fun hostAloneCannotSelectWrongCreator(){
        val sources=listOf(
            source("https://example.org/creator/first"),
            source("https://example.org/creator/second"))
        assertNull(SiteRunControl.matchingSource(
            "https://example.org/", "ADULT", sources))
    }
    @Test fun singleAliasMayMatchOneRegisteredSite(){
        val sources=listOf(source("https://www.example.org/"))
        assertEquals(sources[0],SiteRunControl.matchingSource(
            "https://example.org","ADULT",sources))
    }
    @Test fun areaMustMatch(){
        val sources=listOf(source("https://example.org/","HENTAI"))
        assertNull(SiteRunControl.matchingSource(
            "https://example.org/","ADULT",sources))
    }
}
