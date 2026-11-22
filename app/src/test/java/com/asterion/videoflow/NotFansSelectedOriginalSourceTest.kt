package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class NotFansSelectedOriginalSourceTest {
    private val selected="https://notfans.com/videos/117950/voulezj46/"
    // Real publisher's public download-link shape, observed on selected article.
    private val original="https://notfans.com/get_file/3/"+
        "7a156aad7be55c7095fa33d2268c6ed3c7e0b0e7ce/117000/"+
        "117950/117950.mp4/?download=true&download_filename=voulezj46.mp4"

    @Test fun selectedDownloadIsTiedToExactVideoAndUsesExistingNativeLane() {
        val html="""<main><article>
            <p>Duration: 8:54</p><div class="download">Download:
            <a href="$original">MP4 720p, 90.48 Mb</a></div>
            <aside class="related"><a href="https://notfans.com/get_file/3/abcdefabcdefabcdefabcdef/117000/117951/117951.mp4/?download=true">MP4 1080p</a></aside>
            </article></main>"""
        assertEquals(listOf(original),
            NotFansSelectedOriginalSource.published(selected,html))
        val integrated=PublisherOriginalExtractors.fromHtml(selected,html)
        assertEquals(listOf(original),integrated.map{it.url})
        assertEquals(selected,integrated.single().referer)
        assertTrue(NativeMediaDownloader.isNativeEligible(selected,original))
        assertTrue(NotFansSelectedOriginalSource.verifiedDownload(selected,original))
    }

    @Test fun highestSourcePublishedQualityWinsOnlyForSameVideo() {
        val full=original.replace("MP4","MP4")
        val html="""<main><div class="download">
            <a href="$original">MP4 720p, 90.48 Mb</a>
            <a href="$original&quality=1080">MP4 1080p, 140 Mb</a>
        </div></main>"""
        assertEquals(listOf("$original&quality=1080"),
            NotFansSelectedOriginalSource.published(selected,html))
    }

    @Test fun rejectEveryUnrelatedOrSpoofedMediaRoute() {
        val candidates=listOf(
            original.replace("117950/117950.mp4","117951/117951.mp4"),
            original.replace("117950/117950.mp4","117950/117951.mp4"),
            original.replace("notfans.com","notfans.com.evil.example"),
            original.replace("https://","http://"),
            original.replace("download=true","download=false"),
            original.replace("/get_file/","/videos/"),
            "https://notfans.com/get_file/3/abc/117000/117950/117950.mp4/?download=true",
            original+"#advert",
            original.replace("download=true","download=true&download=true")
        )
        for(candidate in candidates)
            assertFalse(candidate,NotFansSelectedOriginalSource.verifiedDownload(
                selected,candidate))
        assertTrue(NotFansSelectedOriginalSource.published(
            "https://notfans.com/videos/117951/unrelated/",
            "<a href='$original'>MP4 720p, 90.48 Mb</a>").isEmpty())
        assertTrue(NotFansSelectedOriginalSource.published(
            selected,"<a href='$original'>Trailer clip</a>").isEmpty())
        assertNull(NotFansSelectedOriginalSource.selectedId(
            "https://notfans.com/"))
    }

    @Test fun tiedSameQualityConflictsAreNotChosenAtRandom() {
        val other=original+"&variant=other"
        val html="""<a href="$original">MP4 720p, 90.48 Mb</a>
            <a href="$other">MP4 720p, 90.48 Mb</a>"""
        assertTrue(NotFansSelectedOriginalSource.published(selected,html).isEmpty())
    }
}
