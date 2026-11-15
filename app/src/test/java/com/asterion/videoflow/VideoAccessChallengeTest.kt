package com.asterion.videoflow

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class VideoAccessChallengeTest {
    @Test fun http200VerificationShellIsNotTreatedAsSelectedMedia(){
        val selected="https://hentaihaven.xxx/watch/episode-one/"
        val html="""<html><head><title>Just a moment...</title></head>
            <body><p>Checking your browser before accessing the site.</p>
            </body></html>"""
        assertTrue(WebDiscovery.accessChallenge(Jsoup.parse(html,selected)))
    }

    @Test fun realSelectedVideoIsNotFalselyClassifiedAsVerification(){
        val selected="https://hentaihaven.xxx/watch/episode-one/"
        val html="""<html><head><title>Just a moment</title></head>
            <body><main><video src="https://video.example.net/original.mp4"></video>
            </main></body></html>"""
        assertFalse(WebDiscovery.accessChallenge(Jsoup.parse(html,selected)))
    }

    @Test fun ordinaryVideoPageWithoutChallengeIsNotBlocked(){
        assertFalse(WebDiscovery.accessChallenge(
            Jsoup.parse("<html><title>Episode 3</title><p>Watch episode</p></html>",
                "https://hentaihaven.xxx/watch/episode-three/")))
    }
}
