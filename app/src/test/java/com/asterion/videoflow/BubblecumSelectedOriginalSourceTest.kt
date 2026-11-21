package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

/** Real source is first-party KVS flashvars, not a random player/ad/media
 * tag. Signed URL remains opaque and belongs only to the selected article.
 */
class BubblecumSelectedOriginalSourceTest {
    private val selected="https://www.bubblecum.lol/video/selected-scene-2026/"
    private val full="https://www.bubblecum.lol/get_file/0/abCD_efGh12345678901234567890/30645_1080p.mp4/?sig=opaque"
    private fun page(url:String=full)= """
        <article><div id="kt_player"></div>
        <script>
        var flashvars = { video_id: '30645',
          video_url: '$url',
          video_alt_url: 'https://www.bubblecum.lol/get_file/0/alternate_12345678901234567890/30645_720p.mp4/?sig=other'
        };
        kt_player('kt_player',flashvars);
        </script></article>
        <div class="related"><video src="https://www.bubblecum.lol/contents/videos_screenshots/22000/30645/preview.mp4"></video></div>
    """.trimIndent()

    @Test fun selectedSignedFirstPartyOriginalIsNotTrailer(){
        assertEquals(listOf(full),
            BubblecumSelectedOriginalSource.published(selected,page()))
        assertEquals(listOf(full),
            PublisherOriginalExtractors.fromHtml(selected,page()).map{it.url})
    }
    @Test fun rejectPreviewAndUnrelatedOriginOrWrongSelectedArticle(){
        val teaser="https://www.bubblecum.lol/contents/videos_screenshots/22000/30645/preview.mp4"
        assertFalse(BubblecumSelectedOriginalSource.verifiedMedia(selected,teaser))
        assertTrue(BubblecumSelectedOriginalSource.published(selected,page(teaser)).isEmpty())
        assertFalse(BubblecumSelectedOriginalSource.verifiedMedia(selected,
            "https://unrelated.invalid/get_file/0/abCD_efGh12345678901234567890/30645_1080p.mp4/"))
        assertFalse(BubblecumSelectedOriginalSource.verifiedMedia(
            "https://www.bubblecum.lol/models/","https://www.bubblecum.lol/get_file/0/abCD_efGh12345678901234567890/30645_1080p.mp4/"))
    }
    @Test fun ambiguousPlayerOrConfigIsNotAnOriginal(){
        assertTrue(BubblecumSelectedOriginalSource.published(selected,
            page().replace("<div id=\"kt_player\"></div>","")).isEmpty())
        assertTrue(BubblecumSelectedOriginalSource.published(selected,
            page()+page()).isEmpty())
    }
}
