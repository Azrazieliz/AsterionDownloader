package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class PimpBunnySelectedOriginalSourceTest {
    private val selected="https://pimpbunny.com/videos/selected-scene-123"
    private val full="https://www.pimpbunny.com/get_file/0/abCD_efGh12345678901234567890/100000/102030/102030_1080p.mp4/?signature=opaque"
    private fun page(value:String=full)= """
        <main><article><div id="kt_player"></div>
        <script>
        var flashvars = { video_id: '102030',
          video_url: '$value',
          video_alt_url: 'https://www.pimpbunny.com/get_file/0/alternate_12345678901234567890/100000/102030/102030_720p.mp4/?sig=other'
        };
        kt_player('kt_player',flashvars);
        </script></article></main>
        <div class="recommended"><video
           src="https://www.pimpbunny.com/contents/videos_screenshots/100000/102030/preview.mp4"></video></div>
    """.trimIndent()
    @Test fun realPlayerSignedMp4IsNotRelatedPreview(){
        assertEquals(listOf(full),
            PimpBunnySelectedOriginalSource.published(selected,page()))
        assertEquals(listOf(full),
            PublisherOriginalExtractors.fromHtml(selected,page()).map{it.url})
        assertTrue(PimpBunnySelectedOriginalSource.verifiedMedia(selected,full))
        // The old dispatcher used the verified flag but failed to admit this
        // very same first-party signed file, falling into yt-dlp/PHP.
        assertTrue(NativeMediaDownloader.isNativeEligible(selected,full,true))
    }
    @Test fun neverPromotePreviewOrExternalMedia(){
        assertTrue(PimpBunnySelectedOriginalSource.published(selected,page(
            "https://www.pimpbunny.com/contents/videos_screenshots/100000/102030/preview.mp4")).isEmpty())
        assertFalse(PimpBunnySelectedOriginalSource.verifiedMedia(selected,
            full.replace("pimpbunny.com","fake.example")))
        assertTrue(PimpBunnySelectedOriginalSource.published(selected,
            page().replace("<div id=\"kt_player\"></div>","")).isEmpty())
        assertTrue(PimpBunnySelectedOriginalSource.published(selected,
            page()+page()).isEmpty())
    }
}
