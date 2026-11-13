package com.asterion.videoflow

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class ArchivebatePublishedEmbedTest {
    @Test fun selectedRealWatchPageAdmitsOnlyPublisherMixdropIframe(){
        val watch="https://archivebate.com/watch/16458050"
        val doc=Jsoup.parse(
            """<html><body>
               <iframe src="https://mixdrop.ag/e/ABcdEF123" allowfullscreen></iframe>
               <iframe src="https://malicious.example/e/ABcdEF123" allowfullscreen></iframe>
               <iframe src="https://mixdrop.ag/ads/click?url=x" allowfullscreen></iframe>
               </body></html>""",watch)
        assertEquals(listOf("https://mixdrop.ag/e/ABcdEF123"),
            WebDiscovery.publishedEmbeds(doc,watch))
    }

    @Test fun genericListingCannotAcceptAnyOffsiteIframe(){
        val listing="https://archivebate.com/"
        val doc=Jsoup.parse(
            "<iframe src='https://mixdrop.ag/e/ABcdEF123' allowfullscreen></iframe>",
            listing)
        assertTrue(WebDiscovery.publishedEmbeds(doc,listing).isEmpty())
        assertTrue(WebDiscovery.publishedEmbeds(doc,
            "https://archivebate.com/2257").isEmpty())
    }
}
