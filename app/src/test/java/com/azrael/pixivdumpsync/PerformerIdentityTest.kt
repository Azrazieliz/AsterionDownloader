package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/** A name or a video-title hit alone is never existence evidence. */
class PerformerIdentityTest {
    @Test fun officialProfileWithMatchingVisibleNameCanBeVerified(){
        val url="https://www.pornhub.com/model/jane-example"
        val doc=Jsoup.parse("""<html><head>
            <link rel="canonical" href="https://www.pornhub.com/model/jane-example"/>
            </head><body><h1>Jane Example</h1></body></html>""",url)
        assertTrue(PerformerIdentity.matchesEvidence("Jane Example",url,doc))
    }
    @Test fun inventedNamesAndSearchPagesCannotBeFollowed(){
        val url="https://www.pornhub.com/model/unrelated-person"
        val doc=Jsoup.parse("<html><h1>Not The Same Person</h1></html>",url)
        assertFalse(PerformerIdentity.matchesEvidence("Unrelated Person",url,doc))
        assertFalse(PerformerIdentity.isProfileUrl(
            "Unrelated Person","https://www.pornhub.com/video/search?q=Unrelated+Person"))
    }
    @Test fun crossDomainLookalikesCannotVerifyPerformers(){
        assertFalse(PerformerIdentity.isProfileUrl(
            "Jane Example","https://www.pornhub.com.evil.example/model/jane-example"))
        assertFalse(PerformerIdentity.isProfileUrl(
            "Jane Example","http://www.pornhub.com/model/jane-example"))
        assertFalse(PerformerIdentity.isProfileUrl(
            "Jane Example","https://user:pass@www.pornhub.com/model/jane-example"))
    }
    @Test fun watchPornOfficialArtistProfileRouteIsRecognized() {
        assertTrue(PerformerIdentity.isProfileUrl(
            "Tina Kitsune","https://watchporn.to/models/tina-kitsune/"))
        assertFalse(PerformerIdentity.isProfileUrl(
            "Tina Kitsune","https://watchporn.to/video/tina-kitsune/"))
    }

    @Test fun fullPornerPublishedPersonRouteRequiresExactNameAndCanonical() {
        val url="https://fullporner.com/pornstar/jane-example"
        val page=Jsoup.parse("""<html><head>
            <link rel="canonical" href="https://fullporner.com/pornstar/jane-example"/>
            </head><body><h1>Jane Example</h1></body></html>""",url)
        assertTrue(PerformerIdentity.matchesEvidence("Jane Example",url,page))
        assertFalse(PerformerIdentity.matchesEvidence("Other Performer",url,page))
        assertFalse(PerformerIdentity.isProfileUrl(
            "Jane Example","https://fullporner.com/watch/jane-example"))
    }

    @Test fun cumsRealModelIdMustMatchVisibleIdentity() {
        val url="https://cums.net/model/caryn-beaumont-18aq0o/"
        val doc=Jsoup.parse("""<html><head>
          <link rel="canonical" href="$url"/></head>
          <body><h1>Caryn Beaumont Porn and Nude Videos</h1></body></html>""",url)
        assertTrue(PerformerIdentity.isProfileUrl("Caryn Beaumont",url))
        assertTrue(PerformerIdentity.matchesEvidence("Caryn Beaumont",url,doc))
        assertFalse(PerformerIdentity.matchesEvidence("Wrong Person",url,doc))
        assertFalse(PerformerIdentity.isProfileUrl(
            "Caryn Beaumont","https://cums.net/model/"))
        assertFalse(PerformerIdentity.isProfileUrl(
            "Caryn Beaumont","https://cums.net/video/240284/a-movie/"))
        assertFalse(PerformerIdentity.isProfileUrl(
            "Caryn Beaumont","https://cums.net/model/caryn-beaumont-fake.example/"))
    }

    @Test fun verifiableProfileRoutesAreDistinctFromVideoTitles(){
        assertFalse(PerformerIdentity.isProfileUrl(
            "Jane Example","https://www.xvideos.com/pornstars/jane-example"))
        assertFalse(PerformerIdentity.isProfileUrl(
            "Jane Example","https://www.xvideos.com/video12345/jane-example"))
    }
}
