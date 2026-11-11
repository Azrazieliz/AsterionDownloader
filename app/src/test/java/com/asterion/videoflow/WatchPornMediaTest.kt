package com.asterion.videoflow

import org.junit.Test
import org.junit.Assert.*

class WatchPornMediaTest {
    @Test fun extractsOriginalInsteadOfPreview(){
        val html = "video_url: 'https://watchporn.to/get_file/5/abc/item_720p.mp4/?token=one', " +
            "video_alt_url: 'https://watchporn.to/get_file/5/def/item_1080p.mp4/?token=two', " +
            "preview: 'https://watchporn.to/contents/preview.mp4'"
        assertEquals("https://watchporn.to/get_file/5/def/item_1080p.mp4/?token=two",
            WebDiscovery.watchPornDirect(html))
    }
    @Test fun ignoresExternalMediaHosts(){
        val html="video_url: 'https://unsafe.example/get_file/5/abc/item.mp4?x=3'"
        assertNull(WebDiscovery.watchPornDirect(html))
    }
    @Test fun ignoresPreviewClipLinks(){
        val html="video_url: 'https://watchporn.to/get_file/5/abc/item_preview.mp4'"
        assertNull(WebDiscovery.watchPornDirect(html))
    }
}
