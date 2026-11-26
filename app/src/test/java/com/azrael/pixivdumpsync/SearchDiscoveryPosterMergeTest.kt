package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class SearchDiscoveryPosterMergeTest {
    @Test fun sameResultRetainsPosterFromLowerRankedIndex(){
        val url="https://example.org/video/woodworking"
        val a=RankedHit("Woodworking",url,"Index A",score=200)
        val b=RankedHit("Woodworking",url+"?utm_source=example","Index B",
            score=100,thumbnail="https://example.org/images/wood.jpg")
        val merged=SearchRanking.mergeDiscoveryHit(a,b)
        assertEquals(a.url,merged.url)
        assertEquals(a.score,merged.score)
        assertEquals(b.thumbnail,merged.thumbnail)
    }
    @Test fun unrelatedResultCannotDonatePoster(){
        assertThrows(IllegalArgumentException::class.java){
            SearchRanking.mergeDiscoveryHit(
                RankedHit("One","https://example.org/video/one","A"),
                RankedHit("Two","https://example.org/video/two","B"))
        }
    }
}
