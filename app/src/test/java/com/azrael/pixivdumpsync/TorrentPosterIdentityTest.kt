package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class TorrentPosterIdentityTest {
    @Test fun extensionlessPublisherPosterIsKeptForImageValidation(){
        val hash="0123456789abcdef0123456789abcdef01234567"
        val json="""{"results":[{"title":"Open source release","infohash":"$hash",
            "coverUrl":"https://images.example.org/resize/123?width=400"}]}"""
        assertEquals("https://images.example.org/resize/123?width=400",
            MultiTorrentSearch.bitsearch(json).single().coverUrl)
    }
    @Test fun alternateEncodingOfSameHashCanContributePosterButOtherHashCannot(){
        val hash="0000000000000000000000000000000000000000"
        val first=CatalogHit("Open source release","Index A","magnet:?xt=urn:btih:"+hash,
            CatalogKind.TORRENT,"","")
        val alternate=first.copy(source="Index B",url="magnet:?xt=urn:btih:"+"A".repeat(32),
            coverUrl="https://images.example.org/release/1")
        val unrelated=first.copy(url="magnet:?xt=urn:btih:"+"1".repeat(40),
            coverUrl="https://images.example.org/release/2")
        val merged=MultiTorrentSearch.mergeExactResults(listOf(first,alternate,unrelated))
        assertEquals(2,merged.size)
        assertEquals(first.url,merged.first().url)
        assertEquals(alternate.coverUrl,merged.first().coverUrl)
        assertEquals(unrelated.coverUrl,merged.last().coverUrl)
    }
}
