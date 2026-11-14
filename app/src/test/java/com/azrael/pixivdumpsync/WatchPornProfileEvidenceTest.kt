package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Directory hits and cross-provider redirects must never become follows. */
class WatchPornProfileEvidenceTest {
    private val address="https://watchporn.to/models/jane-example/"
    @Test fun acceptsAccessibleFirstPartyIdentityHeading(){
        val page=Jsoup.parse(
            """<html><head><link rel="canonical" href="$address"/></head>
               <body><h1>Jane Example</h1><a href="/video/a">Film</a></body></html>""",
            address)
        assertTrue(WatchPornPanel.matchesProfile("Jane Example",address,page))
    }
    @Test fun shortInitialsCannotMasqueradeAsDistinctCreator(){
        val url="https://watchporn.to/models/jj/"
        val page=Jsoup.parse("<html><h1>JJ</h1></html>",url)
        assertFalse(WatchPornPanel.matchesProfile("JJ",url,page))
    }
    @Test fun rejectsVideoTitleAndUnrelatedPerformer(){
        val document=Jsoup.parse(
            """<html><h1>Another Person</h1><a title="Jane Example" href="/video/a">
               Jane Example</a></html>""",address)
        assertFalse(WatchPornPanel.matchesProfile("Jane Example",address,document))
    }
    @Test fun rejectsSearchPagesAndOtherProviderDomains(){
        val page=Jsoup.parse("<html><h1>Jane Example</h1></html>",address)
        assertFalse(WatchPornPanel.matchesProfile("Jane Example",
            "https://watchporn.to/search?q=jane",page))
        assertFalse(WatchPornPanel.matchesProfile("Jane Example",
            "https://watchporn.to.evil.example/models/jane-example/",page))
    }
    @Test fun rejectsMismatchedCanonicalDomain(){
        val page=Jsoup.parse("""<html><head>
            <link rel="canonical" href="https://other.example/models/jane-example/"/>
            </head><h1>Jane Example</h1></html>""",address)
        assertFalse(WatchPornPanel.matchesProfile("Jane Example",address,page))
    }
}
