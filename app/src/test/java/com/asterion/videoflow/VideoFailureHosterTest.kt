package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class VideoFailureHosterTest {
    @Test fun unresolvedUserAuthorizedFileHostDoesNotLoopForever(){
        val text="MP4Hentai published host requires a real direct file link or an authorized browser session"
        val kind=VideoFailurePolicy.classify(text)
        assertEquals(VideoFailurePolicy.Kind.AUTH,kind)
        assertEquals(0,VideoFailurePolicy.maxRetries(kind))
    }

    @Test fun unrelatedOrAmbiguousSelectedMediaDoesNotRetry(){
        for(text in listOf(
            "3DHentaiX original identity unverified: selected player must expose exactly one",
            "MP4Hentai host exposed multiple unverified MP4 downloads",
            "MP4Hentai browser exposed ambiguous media files")) {
            val kind=VideoFailurePolicy.classify(text)
            assertEquals(text,VideoFailurePolicy.Kind.UNSUPPORTED,kind)
            assertEquals(0,VideoFailurePolicy.maxRetries(kind))
        }
    }

    @Test fun absentSelectedPlayerDoesNotEnterBlindRetry(){
        for(message in listOf(
            "Selected publisher iframe exposed no attributable direct media",
            "Rule34Video selected page exposed no verified download quality links",
            "Selected article exposed no attributable original")) {
            val kind=VideoFailurePolicy.classify(message)
            assertEquals(message,VideoFailurePolicy.Kind.UNSUPPORTED,kind)
            assertEquals(0,VideoFailurePolicy.maxRetries(kind))
        }
    }
    @Test fun realServer503IsTemporaryNotFalseOriginalSuccess(){
        assertEquals(VideoFailurePolicy.Kind.TRANSIENT,
            VideoFailurePolicy.classify("Provider HTTP 503 · request unsuccessful"))
    }

    @Test fun siteVerificationIsActionableRatherThanEndlesslyRetried(){
        val kind=VideoFailurePolicy.classify(
            "HentaiHaven selected episode remains behind site verification")
        assertEquals(VideoFailurePolicy.Kind.AUTH,kind)
        assertEquals(0,VideoFailurePolicy.maxRetries(kind))
    }
}
