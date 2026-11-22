package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class VideoHlsDispatchTest {
    @Test fun selectedSignedPublisherHlsUsesTypedEngineFirst(){
        assertTrue(VideoHlsDispatch.isSelectedManifest(
            "https://tnmr.org/hls4/abc123/master.m3u8?signature=publisher"))
        assertTrue(VideoHlsDispatch.isSelectedManifest(
            "https://hm-h.phncdn.com/videos/abc123/index.m3u8?token=selected"))
        assertTrue(VideoHlsDispatch.isSelectedManifest(
            "https://media.example/full/manifest.mpd"))
    }
    @Test fun signedPublisherRuntimeIsPrimedBeforeFetchingExpiringHls() {
        // The restored LU/SB non-JK source must initialize Python BEFORE
        // obtaining the selected publisher's short-lived HLS signature.
        assertTrue(VideoHlsDispatch.needsRuntimeBeforeSource(
            "https://jav.guru/1077002/doks-695-original-scene/"))
        assertTrue(VideoHlsDispatch.needsRuntimeBeforeSource(
            "https://www.pornhub.com/view_video.php?viewkey=ph1234567"))
        assertTrue(VideoHlsDispatch.needsRuntimeBeforeSource(
            "https://m.pornhub.com/view_video.php?viewkey=ph1234567"))
        assertTrue(VideoHlsDispatch.needsRuntimeBeforeSource(
            "https://hentaihaven.xxx/watch/selected-series/episode-2"))
        assertTrue(VideoHlsDispatch.needsRuntimeBeforeSource(
            "https://taiav.com/cn/movie/654424d67b410d2615db16e5"))
        assertTrue(VideoHlsDispatch.needsRuntimeBeforeSource(
            "https://en.xchina.co/video/abcdef1234567890"))
        assertTrue(VideoHlsDispatch.needsRuntimeBeforeSource(
            "https://xchina.co/video/id-abcdef1234567890"))
        assertFalse(VideoHlsDispatch.needsRuntimeBeforeSource(
            "https://en.xchina.co/"))
        assertFalse(VideoHlsDispatch.needsRuntimeBeforeSource(
            "https://taiav.com/cn/"))
        assertFalse(VideoHlsDispatch.needsRuntimeBeforeSource(
            "https://taiav.com/previewvideo?id=654424d67b410d2615db16e5"))
        for(url in listOf(
            "https://jav.guru/",
            "https://jav.guru/category/test/",
            "https://www.pornhub.com/",
            "https://www.pornhub.com/view_video.php?viewkey=bad%00",
            "https://jav.guru.evil.example/123456/scene/",
            "https://jav.guru/123456/scene/#fragment",
            "http://jav.guru/123456/scene/",
            "https://www.pornhub.com/view_video.php?viewkey=ph1234&viewkey=ph5678"
        ))assertFalse(url,VideoHlsDispatch.needsRuntimeBeforeSource(url))
    }

    @Test fun watchPagesAndUntrustedManifestShapesAreNotPromoted(){
        for(url in listOf(
            "https://www.pornhub.com/view_video.php?viewkey=abc12345",
            "https://jav.guru/123456/actual-post/",
            "http://tnmr.org/hls/abc/master.m3u8",
            "https://user:secret@tnmr.org/hls/abc/master.m3u8",
            "https://tnmr.org/hls/abc/master.m3u8#fragment",
            "https://tnmr.org/hls/abc/master.m3u8/preview",
            "https://tnmr.org/playlist.html?file=master.m3u8"
        )) assertFalse(url,VideoHlsDispatch.isSelectedManifest(url))
    }
}
