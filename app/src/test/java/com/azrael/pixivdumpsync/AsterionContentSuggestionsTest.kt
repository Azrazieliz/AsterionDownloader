package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class AsterionContentSuggestionsTest {
    @Test fun recommendationModulesPreserveOriginalContentClassification(){
        val cases=mapOf(
            "COSPLAY" to "COSPLAY",
            "ARTWORK" to "ARTWORK",
            "CREATORS" to "CREATORS",
            "BOOKS" to "BOOKS",
            "COMICS" to "COMICS",
            "GAMES" to "GAMES",
            "ASSETS" to "ASSETS",
            "KOIKATSU" to "KOIKATSU",
            "ADULT" to "VIDEOS",
            "HENTAI" to "VIDEOS",
            "VIDEOS" to "VIDEOS"
        )
        for((source,expected) in cases)
            assertEquals("Misrouted "+source,expected,
                AsterionContentSuggestions.suggestionArea(source))
    }
}
