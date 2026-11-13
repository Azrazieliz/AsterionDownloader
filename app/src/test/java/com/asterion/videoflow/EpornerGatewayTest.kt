package com.asterion.videoflow

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class EpornerGatewayTest {
    @Test fun officialAgeGatewayIsNotVideoMetadata(){
        val page=Jsoup.parse("<html><head><title>Age verification</title></head>"+
            "<body>Parental control. Only for adults.</body></html>",
            "https://www.eporner.com/video-MSADu4C0K92/")
        assertTrue(WebDiscovery.epornerAccessGate(page))
    }
    @Test fun actualMediaElementIsNotMisclassifiedAsGateway(){
        val page=Jsoup.parse("<title>Age notice</title><body>"+
            "<video src='https://media.example.com/original.mp4'></video>"+
            "Only for adults</body>","https://www.eporner.com/video-MSADu4C0K92/")
        assertFalse(WebDiscovery.epornerAccessGate(page))
    }
    @Test fun unrelatedSitesNeverUseEpornerRule(){
        val page=Jsoup.parse("<title>Age verification</title>Parental control",
            "https://onlyhentaistuff.com/videos/11877/example/")
        assertFalse(WebDiscovery.epornerAccessGate(page))
    }
}
