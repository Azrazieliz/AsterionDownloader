package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class TaiAvOriginalSourceTest {
    private val id="654424d67b410d2615db16e5"
    private val selected="https://taiav.com/cn/movie/$id"
    private val article="""
        <html><body>
         <select class="hdselect"><option value="1280">HD</option></select>
         <script>var id = "$id";
           // Publisher's exact selected original, not /previewvideo
           $.ajax({type:"GET",url:`/api/getmovie?type=${'$'}{hd}&id=$id`,
             success:function(data){if(data.m3u8)player.load(data.m3u8)}})
         </script>
        </body></html>
    """.trimIndent()

    @Test fun selectsOnlyPublishedQualityAndSelectedMovieId(){
        val requests=TaiAvOriginalSource.selectedRequest(selected,article)
        assertEquals(1,requests.size)
        assertEquals("1280",requests.single().quality)
        assertEquals(id,requests.single().movieId)
        assertEquals("https://taiav.com/api/getmovie?type=1280&id=$id",
            requests.single().endpoint)
        assertTrue(TaiAvOriginalSource.selectedRequest(
            "https://taiav.com/cn/",article).isEmpty())
        assertTrue(TaiAvOriginalSource.selectedRequest(
            "https://taiav.com.evil.example/cn/movie/$id",article).isEmpty())
        assertTrue(TaiAvOriginalSource.selectedRequest(
            selected.replace(id,"a".repeat(24)),article).isEmpty())
    }

    @Test fun relativeFullPlaylistAcceptedButPreviewAndAdsRejected(){
        val request=TaiAvOriginalSource.selectedRequest(
            selected,article).single()
        val relative="""{"m3u8":"/hls/$id/1280.m3u8?expire=123"}"""
        assertEquals("https://taiav.com/hls/$id/1280.m3u8?expire=123",
            TaiAvOriginalSource.verifiedHls(request,relative))
        assertNull(TaiAvOriginalSource.verifiedHls(request,
            """{"previewvideo":"https://taiav.com/preview/short.mp4"}"""))
        assertNull(TaiAvOriginalSource.verifiedHls(request,
            """{"m3u8":"/previewvideo/short.m3u8"}"""))
        assertNull(TaiAvOriginalSource.verifiedHls(request,
            """{"m3u8":"https://taiav.com/trailer.mp4"}"""))
        assertNull(TaiAvOriginalSource.verifiedHls(request,
            """{"m3u8":"http://taiav.com/media/full.m3u8"}"""))
    }
}
