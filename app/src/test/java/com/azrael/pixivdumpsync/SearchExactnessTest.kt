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
    @Test fun unifiedVideoNameRequiresAdjacentOrderedWords() {
        assertTrue(SearchExactness.mediaMatches("bella Alice",
            "Bella Alice - original selected recording"))
        assertTrue(SearchExactness.mediaMatches("eva Elfie",
            "Eva Elfie official scene"))
        assertFalse(SearchExactness.mediaMatches("bella Alice",
            "Barbara Bella and Alice Marshall - party"))
        assertFalse(SearchExactness.mediaMatches("bella Alice",
            "Charlyse Bella And Alice In Obey Your Owner"))
        assertFalse(SearchExactness.mediaMatches("bella Alice",
            "Alice Bella recorded at home"))
        assertFalse(SearchExactness.mediaMatches("Bella Alice",
            "Bella Alicez unrelated"))
        // Single-token discovery remains usable; only names were overly broad.
        assertTrue(SearchExactness.mediaMatches("Alice","Alice original scene"))
    }

    @Test fun broadDescriptionCanReorderWordsWithoutInvitingWrongPerformers() {
        assertTrue(SearchExactness.mediaMatches("space documentary 2026",
            "2026 documentary about deep space"))
        assertTrue(SearchExactness.mediaMatches("nature documentary",
            "Documentary about remote nature"))
        assertFalse(SearchExactness.mediaMatches("Bella Alice",
            "Barbara Bella and Alice Marshall"))
    }

}
