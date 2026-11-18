package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

/** Offline selected publisher fixture only, never a completed PC or Android original. */
class JavGuruJkSelectedOriginalTest {
    private val embed="https://maxstream.org/e/OfficialJkOriginal01"
    private val original="https://s4.maxstream.org/hls/selected-mxgs1452/index-v1-a1.m3u8?t=publisher-signed&f=123"

    private fun packed(url:String):String {
        val symbols=MutableList(36){""}
        symbols[35]="file"
        // Dean Edwards P.A.C.K.E.R word 35 ("z") expands to "file".
        val source="z:\"$url\"; const selectedPlayer=1;"
        return """eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('$source',36,36,'${symbols.joinToString("|")}'.split('|')))"""
    }
    private fun doc(vararg fragments:String):String =
        "<html><div id='player'></div>"+
        fragments.joinToString(""){"<script>$it</script>"}+"</html>"

    @Test fun publisherSelectedJkPackedHlsIsAcceptedOnlyFromExactPlayer(){
        assertEquals(original,JavGuruOriginalSource.selectedHls(
            embed,doc(packed(original))))
    }
    @Test fun unrelatedSignedCdnOrMissingSignatureCannotBeOriginal(){
        assertNull(JavGuruOriginalSource.selectedHls(embed,doc(
            packed("https://s4.maxstream.org.evil.example/hls/x/original.m3u8?t=signed"))))
        assertNull(JavGuruOriginalSource.selectedHls(embed,doc(
            packed("https://s4.maxstream.org/hls/x/original.m3u8"))))
        assertNull(JavGuruOriginalSource.selectedHls(embed,doc(
            packed("https://s4.maxstream.org/hls/../ads/video.m3u8?t=bad"))))
    }
    @Test fun wrongSelectedPlayerAndUnpackedAdvertisementsDoNotBecomeOriginal(){
        assertNull(JavGuruOriginalSource.selectedHls(
            "https://ads.example/e/OfficialJkOriginal01",doc(packed(original))))
        assertNull(JavGuruOriginalSource.selectedHls(
            embed,doc("var file='$original';")))
        assertNull(JavGuruOriginalSource.selectedHls(
            "https://maxstream.org/watch/OfficialJkOriginal01",
            doc(packed(original))))
    }
    @Test fun independentConflictingPackedMediaAreRejected(){
        val other="https://s4.maxstream.org/hls/another-scene/index-v1-a1.m3u8?t=other"
        assertNull(JavGuruOriginalSource.selectedHls(embed,doc(
            packed(original),packed(other))))
    }
}
