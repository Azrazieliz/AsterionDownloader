package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

/** Offline source fixture only; never a live complete media receipt. */
class JavGuruSbSelectedOriginalTest {
    private val embed="https://javclan.com/e/SbSelectedAbc12"
    private val media="https://g6example.premilkyway.com/hls2/01/abc123de/stream.urlset/master.m3u8?s=signature&e=123"

    private fun packed(url:String):String {
        val terms=MutableList(36){""}
        terms[35]="var"
        terms[34]="links"
        terms[33]="hls2"
        terms[32]="jwplayer"
        terms[31]="setup"
        terms[30]="sources"
        terms[29]="file"
        terms[28]="hls4"
        terms[27]="hls3"
        val source="z y={\"x\":\"$url\"}; w(\"vplayer\").v({u:[{t:y.s||y.r||y.x}]})"
        return """eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('$source',36,36,'${{terms.joinToString("|")}'.split('|')))"""
    }

    @Test fun ownerSelectedSbPackedSignedCdnHlsIsAdmitted() {
        assertEquals(media,JavGuruOriginalSource.selectedHls(
            embed,"<html><script>${{packed(media)}</script></html>"))
    }

    @Test fun ownerSelectedSbRejectsUnrelatedSourceAndUnsafeHost() {
        assertNull(JavGuruOriginalSource.selectedHls(
            embed,"<html><script>${{packed("https://g6example.premilkyway.com.evil.example/hls2/01/abc123de/stream.urlset/master.m3u8?s=abc")}</script></html>"))
        assertNull(JavGuruOriginalSource.selectedHls(
            embed,"<html><script>${{packed("https://g6example.premilkyway.com/hls2/../ads/stream.urlset/master.m3u8?s=abc")}</script></html>"))
        assertNull(JavGuruOriginalSource.selectedHls(
            "https://go.mayzaent.com/e/adid111111",
            "<html><script>${{packed(media)}</script></html>"))
    }

    @Test fun unpackedOrUnrelatedInlineHtmlCannotBecomeMedia() {
        assertNull(JavGuruOriginalSource.selectedHls(
            embed,"<html><script>var links={hls2:'$media'};</script></html>"))
    }
}
