package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test
import org.jsoup.Jsoup

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

    @Test fun incompleteSelectedNonJkShellTriggersOneAuthorisedJkHydration(){
        val selected="https://jav.guru/1234567/owner-selected-original/"
        val html="<article id='post-1234567'>"+
            "<a class='wp-btn-iframe__shortcode' data-localize='sb'>STREAM SB</a>"+
            "</article><aside><a class='wp-btn-iframe__shortcode'>STREAM JK</a></aside>"
        val doc=Jsoup.parse(html,selected)
        val sb=listOf(JavGuruOriginalSource.Player(
            "SB","https://jav.guru/searcho/selected-publication"))
        assertTrue(SiteSpecificOriginalSources.shouldInspectSelectedJk(
            doc,selected,sb))
        assertFalse(SiteSpecificOriginalSources.shouldInspectSelectedJk(
            doc,selected,sb,nativeJkOnly=true))
        assertFalse(SiteSpecificOriginalSources.shouldInspectSelectedJk(
            doc,selected,sb+JavGuruOriginalSource.Player(
                "JK","https://jav.guru/searcho/selected-jk")))
        assertFalse(SiteSpecificOriginalSources.shouldInspectSelectedJk(
            doc,"https://jav.guru/1234568/other-selected-item/",sb))
        assertFalse(SiteSpecificOriginalSources.shouldInspectSelectedJk(
            doc,"https://jav.guru/category/video/",sb))
        // A thin first-party JS shell may contain no article markup.
        // The authorised selected browser read must still be attempted.
        assertTrue(SiteSpecificOriginalSources.shouldInspectSelectedJk(
            Jsoup.parse("<aside><a class='wp-btn-iframe__shortcode'>STREAM JK</a></aside>",
                selected),selected,sb))
    }

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
