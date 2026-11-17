package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

/** Offline source fixture only; never a live complete media receipt. */
class JavGuruSbSelectedOriginalTest {
    private val embed="https://javclan.com/e/SbSelectedAbc12"
    private val media="https://javclan.com/stream/SbSelectedAbc12/level/master.m3u8"

    private fun packed(url:String):String {
        val terms=MutableList(36){""}
        terms[35]="var"
        terms[34]="links"
        terms[33]="hls4"
        val source="z y={x:\"$url\"}"
        return """eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('$source',36,36,'${{terms.joinToString("|")}'.split('|')))"""
    }

    @Test fun ownerSelectedSbPackedFirstPartyManifestIsAdmitted() {
        assertEquals(media,JavGuruOriginalSource.selectedHls(
            embed,"<html><script>${{packed(media)}</script></html>"))
    }

    @Test fun ownerSelectedSbRejectsUnrelatedSourceAndUnsafeHost() {
        assertNull(JavGuruOriginalSource.selectedHls(
            embed,"<html><script>${{packed("https://javclan.com.evil.example/stream/SbSelectedAbc12/level/master.m3u8")}</script></html>"))
        assertNull(JavGuruOriginalSource.selectedHls(
            embed,"<html><script>${{packed("https://javclan.com/stream/../ads/master.m3u8")}</script></html>"))
        assertNull(JavGuruOriginalSource.selectedHls(
            "https://go.mayzaent.com/e/adid111111",
            "<html><script>${{packed(media)}</script></html>"))
    }

    @Test fun unpackedOrUnrelatedInlineHtmlCannotBecomeMedia() {
        assertNull(JavGuruOriginalSource.selectedHls(
            embed,"<html><script>var links={hls4:'$media'};</script></html>"))
    }
}
