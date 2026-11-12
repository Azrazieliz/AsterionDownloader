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

    @Test fun verifiableProfileRoutesAreDistinctFromVideoTitles(){
        assertTrue(PerformerIdentity.isProfileUrl(
            "Jane Example","https://www.xvideos.com/pornstars/jane-example"))
        assertFalse(PerformerIdentity.isProfileUrl(
            "Jane Example","https://www.xvideos.com/video12345/jane-example"))
    }
}
