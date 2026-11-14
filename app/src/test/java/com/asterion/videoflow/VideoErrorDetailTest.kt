package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ExecutionException

class VideoErrorDetailTest {
    @Test fun unwrapsNestedExceptionsAndRedactsSignedMediaUrls(){
        val nested=IllegalStateException(
            "Unable to extract: https://cdn.example/video.mp4?token=secret123")
        val message=VideoErrorDetail.summary(ExecutionException("worker",nested))
        assertTrue(message.contains("cannot resolve",true))
        assertFalse(message.contains("cdn.example"))
        assertFalse(message.contains("secret123"))
    }

    @Test fun providerDenialsAreActionableNotUnclassifiedPythonStrings(){
        val nested=IllegalStateException("HTTP Error 403: Forbidden")
        val message=VideoErrorDetail.summary(ExecutionException(nested))
        assertTrue(message.contains("401/403"))
        assertFalse(message.contains("ExecutionException"))
        assertEquals(VideoFailurePolicy.Kind.AUTH,
            VideoFailurePolicy.classify(message))
    }

    @Test fun rateLimitingIsNotMisreportedAsAnAccountProblem(){
        val msg=VideoErrorDetail.summary(
            IllegalStateException("HTTP Error 429: Too Many Requests"))
        assertTrue(msg.contains("rate-limited",true))
        assertEquals(VideoFailurePolicy.Kind.RATE_LIMIT,VideoFailurePolicy.classify(msg))
    }

    @Test fun unsupportedPublisherPlayerIsNonRetryableWithoutRepair(){
        val msg=VideoErrorDetail.summary(
            IllegalStateException("Unsupported URL: https://video.example/watch/3"))
        assertTrue(msg.contains("extractor repair required"))
        assertEquals(VideoFailurePolicy.Kind.UNSUPPORTED,VideoFailurePolicy.classify(msg))
        assertFalse(msg.contains("video.example"))
    }

    @Test fun pythonRuntimeDependencyFailureIsDistinct(){
        val msg=VideoErrorDetail.summary(
            ExecutionException(IllegalStateException("ModuleNotFoundError: No module named yt_dlp")))
        assertTrue(msg.contains("dependency",true))
    }
}
