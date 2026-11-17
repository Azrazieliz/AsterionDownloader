package com.asterion.videoflow

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class SiteSpecificPlayerPolicyTest {
    private val page="https://nhplayer.com/v/Z2flAXXPucyCbVJ/"
    @Test fun actualVerificationPagesAreNotSelectedMedia(){
        for(html in listOf(
            "<html><head><title>Just a moment...</title></head><body>Checking your browser</body></html>",
            "<html><body><form id='challenge-form' action='/challenge'></form></body></html>",
            "<html><body><div class='cf-turnstile'></div></body></html>",
            "<html><body><h1>Verify you are human</h1></body></html>"
        ))assertTrue(SiteSpecificPlayerPolicy.accessGate(Jsoup.parse(html,page)))
    }
    @Test fun selectedPlayerVideoIsNotMistakenForAccessChallenge(){
        val html="<html><head><title>Video</title></head><body>"+
            "<main><video src='https://media.example/selected-original.mp4'></video></main>"+
            "<p>Sign in for comments</p></body></html>"
        assertFalse(SiteSpecificPlayerPolicy.accessGate(Jsoup.parse(html,page)))
    }
    @Test fun unknownEmptyPageIsNotInventedMediaOrDeclaredACaptcha(){
        assertFalse(SiteSpecificPlayerPolicy.accessGate(
            Jsoup.parse("<html><body><h1>Welcome</h1></body></html>",page)))
    }
}