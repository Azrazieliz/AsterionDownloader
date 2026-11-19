package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class OnlyHentaiKvsOriginalSourceTest {
    private val selected="https://www.onlyhentaistuff.com/videos/12345/publisher-selected-work/"
    private fun page(id:String="12345")="""
        <html><body><script>
        var flashvars = {video_id:'$id',
          video_url:'function/0/official-encoded-source',
          video_alt_url:'function/0/alternate'};
        kt_player('kt_player', 'kt_player.swf', flashvars);
        </script><div id="kt_player"></div></body></html>
    """.trimIndent()
    private fun rendered(id:String="12345",source:String=
        "https://media.example.org/selected-original.mp4")="""
        <html><body><main><div class="video-player">
        <div id="kt_player" data-selected-page-id="$id">
          <video src="$source" data-duration="1800"></video>
        </div></div></main></body></html>
    """.trimIndent()

    @Test fun recognizesRealThreeLevelPublisherRoute(){
        assertEquals("12345",OnlyHentaiKvsOriginalSource.selectedId(selected))
        assertNull(OnlyHentaiKvsOriginalSource.selectedId(
            "https://onlyhentaistuff.com/videos/12345"))
        assertNull(OnlyHentaiKvsOriginalSource.selectedId(
            "https://evilonlyhentaistuff.com/videos/12345/work"))
    }
    @Test fun requiresExactPublisherVideoIdAndPlayer(){
        assertTrue(OnlyHentaiKvsOriginalSource.hasSelectedConfig(
            selected,page()))
        // The KVS internal video_id may differ from the article's URL
        // numeric segment: the selected, unique player still owns the media.
        assertTrue(OnlyHentaiKvsOriginalSource.hasSelectedConfig(
            selected,page("76543")))
        assertFalse(OnlyHentaiKvsOriginalSource.hasSelectedConfig(
            selected,page().replace("kt_player(", "other_player(")))
        assertFalse(OnlyHentaiKvsOriginalSource.hasSelectedConfig(
            selected,"<html><video src='https://ads.example.org/ad.mp4'></html>"))
    }
    @Test fun acceptsOnlyExactSelectedRenderedMedia(){
        assertEquals("https://media.example.org/selected-original.mp4",
            OnlyHentaiKvsOriginalSource.rendered(selected,rendered()))
        assertNull(OnlyHentaiKvsOriginalSource.rendered(
            selected,rendered("76543")))
        assertNull(OnlyHentaiKvsOriginalSource.rendered(selected,
            rendered(source="https://media.example.org/preview.mp4")))
        assertNull(OnlyHentaiKvsOriginalSource.rendered(selected,
            rendered(source="https://media.example.org/selected-original.html")))
    }
    @Test fun rejectsAmbiguousMultiplePlayers(){
        val one=rendered()
        val duplicate=one.replace(
            "</video>",
            "</video><video src='https://media.example.org/another.mp4'></video>")
        assertNull(OnlyHentaiKvsOriginalSource.rendered(selected,duplicate))
    }
}
