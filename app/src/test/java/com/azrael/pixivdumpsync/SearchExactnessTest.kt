package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class SearchExactnessTest {
    @Test fun exactArtistMustMatchEveryNameToken() {
        assertTrue(SearchExactness.matches("Molly Redwolf","Molly Redwolf official videos"))
        assertFalse(SearchExactness.matches("Molly Redwolf","Molly Jane videos"))
    }
    @Test fun gameTitlesDoNotPassByVideoSubject() {
        assertFalse(SearchExactness.matches("Rance Quest","Rance XIII gameplay unrelated"))
        assertTrue(SearchExactness.matches("Rance Quest","[PC] Rance Quest Visual Novel"))
        assertFalse(GameTorrentPolicy.hasGamePayload(listOf("Rance Quest 1080p.mkv")))
    }
    @Test fun bookTitlesDoNotPassOnOneCommonWord() {
        assertFalse(SearchExactness.matches("The Silent Garden","The Silent River"))
        assertTrue(SearchExactness.matches("The Silent Garden","The Silent Garden EPUB"))
    }
    @Test fun normalizedJapaneseAndPunctuation() {
        assertTrue(SearchExactness.matches("あおい・空","あおい 空: PC edition"))
        assertTrue(SearchExactness.matches("BLUE ARCHIVE","Blue Archive (2026)"))
    }
}
