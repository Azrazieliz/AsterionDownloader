package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class VideoDiagnosticsReportTest {
    @Test fun removesBearerCookiesAndSignedMediaLinkFromDiagnostic(){
        val raw="HTTP 403 https://cdn.example/scene.mp4?token=SECRET "+
            "Authorization: bearer_secret Cookie: private-session"
        val safe=VideoDiagnosticsReport.sanitize(raw)
        assertFalse(safe.contains("cdn.example"))
        assertFalse(safe.contains("SECRET"))
        assertFalse(safe.contains("private-session"))
        assertFalse(safe.contains("bearer_secret"))
        assertTrue(safe.contains("403"))
    }

    @Test fun stripsNewlinesAndCapsUntrustedErrorDetail(){
        val safe=VideoDiagnosticsReport.sanitize("error\r\n"+"x".repeat(2000))
        assertFalse(safe.contains("\n"))
        assertTrue(safe.length<=320)
    }
}
