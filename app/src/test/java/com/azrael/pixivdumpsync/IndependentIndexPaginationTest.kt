package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class IndependentIndexPaginationTest {
    @Test fun generalVideoIndexUsesRealOffset(){
        val first=AsterionSearchHub.peerTubeSearchUrl("woodworking tutorial",0)
        val third=AsterionSearchHub.peerTubeSearchUrl("woodworking tutorial",2)
        assertTrue(first.endsWith("&count=30&start=0"))
        assertTrue(third.endsWith("&count=30&start=60"))
        assertTrue(third.contains("woodworking+tutorial"))
    }
    @Test fun configuredFederatedIndexRetainsIndependentPageNumber(){
        val first=KeywordSources.federatedIndexUrl("https://search.example/","open source",0)
        val next=KeywordSources.federatedIndexUrl("https://search.example/","open source",1)
        assertTrue(first.endsWith("&pageno=1"))
        assertTrue(next.endsWith("&pageno=2"))
        assertTrue(next.contains("q=open+source"))
    }
}
