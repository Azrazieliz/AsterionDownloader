package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class Rule34VideoOriginalSourceTest {
    private val selected="https://rule34video.com/video/4727779/female-v-x-bbc-reverse-cowgirl/"
    @Test fun realPublishedQualityLabelsProvideTheSelectedDownload() {
        val html="""<div class="download-options">
            <a href="/get_file/123/456789/quality_720.mp4?download=true">MP4 720p</a>
            <a href="https://rule34video.com/get_file/123/456790/quality_1080.mp4?download=true">MP4 1080p</a>
            </div>"""
        val originals=Rule34VideoOriginalSource.parse(html,selected)
        assertEquals(2,originals.size)
        assertEquals(1080,originals[0].quality)
        assertEquals("https://rule34video.com/get_file/123/456790/quality_1080.mp4?download=true",originals[0].url)
        assertEquals("mp4",originals[0].format)
        assertEquals(originals[0].url,
            PublisherOriginalExtractors.fromHtml(selected,html).first().url)
    }
    @Test fun currentLiveDownloadLabelsConcatenateQualityAndFileSize() {
        val html="""<div class="download-options">
            <a href="/get_file/2026/1/quality_360.mp4?download=true">MP4 360p1 MB</a>
            <a href="/get_file/2026/1/quality_720.mp4?download=true">MP4 720p3 MB</a>
            <a href="/get_file/2026/1/quality_480.mp4?download=true">MP4 480p2 MB</a>
            <a href="/get_file/2026/1/preview.mp4?download=true">MP4 1080preview</a>
            </div>"""
        val formats=Rule34VideoOriginalSource.parse(html,selected)
        assertEquals(listOf(720,480,360),formats.map{it.quality})
        assertTrue(formats.all{it.url.contains("/get_file/")})
        assertTrue(formats.all{NativeMediaDownloader.isNativeEligible(
            selected,it.url,true)})
    }
    @Test fun noUnrelatedAdPosterPreviewOrNoDownloadLink() {
        val html="""<div><a href="https://cdn.bad.example/preview.mp4">MP4 1080p</a>
           <a href="https://ads.example/thing?download=true">Advertising banner</a>
           <a href="/get_file/123/456789/sample.mp4?download=true">MP4 720p</a>
           <a href="/get_file/123/456789/quality.mp4?download=maybe">MP4 720p</a>
           </div>"""
        assertTrue(Rule34VideoOriginalSource.parse(html,selected).isEmpty())
    }
    @Test fun extensionlessPublisherDownloadUsesVerifiedNativeMp4NotGenericPage() {
        val address="https://rule34video.com/get_file/123/long-real-content-id?download=true"
        assertTrue(Rule34VideoOriginalSource.firstPartyDownload(selected,address))
        assertTrue(Rule34VideoOriginalSource.isPublisherMediaUrl(address))
        assertTrue(NativeMediaDownloader.isNativeEligible(selected,address,true))
        assertFalse(NativeMediaDownloader.isDirectMediaUrl(address))
        assertFalse(Rule34VideoOriginalSource.firstPartyDownload(
            selected,"https://rule34video.com/get_file/123/not-media?download=false"))
        assertFalse(Rule34VideoOriginalSource.firstPartyDownload(
            selected,"https://lookalike.rule34video.com.evil.example/get_file/123/?download=true"))
        assertFalse(Rule34VideoOriginalSource.firstPartyDownload(
            selected,"https://rule34video.com/tags/123/?download=true"))
    }

    @Test fun exactPublisherRoutesOnly() {
        assertEquals("4727779",Rule34VideoOriginalSource.videoId(selected))
        assertNull(Rule34VideoOriginalSource.videoId("https://rule34video.com/"))
        assertNull(Rule34VideoOriginalSource.videoId("https://rule34video.com/tags/9/"))
        assertNull(Rule34VideoOriginalSource.videoId("https://rule34video.com.evil.example/video/4727779/"))
    }
}
