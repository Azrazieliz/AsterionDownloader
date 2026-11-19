package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class HentaiVostaPolicyTest {
    @Test fun allAudioSelectionsRequireExplicitJapaneseOriginalLanguage(){
        val options=HentaiVostaPolicy.selectedAudioFormat.split('/')
        assertEquals(4,options.size)
        assertTrue(options.all{
            it.contains("+ba[language=ja]") ||
            it.contains("+ba[language=jpn]")
        })
        assertFalse(options.any{
            it.contains("ba[language=en]") ||
            it.endsWith("+ba") || it=="b"
        })
        assertEquals("en",HentaiVostaPolicy.englishSubtitleLanguage)
    }

    @Test fun observedEnglishOnlyAudioIsNotAClaimOfVosta(){
        val available=setOf("audio-English")
        val required=setOf("ja","jpn")
        assertFalse(available.any{item->
            required.any{lang->item.equals("audio-"+lang,ignoreCase=true)}
        })
        // The publisher's current only audio label is English,
        // though the actual spoken language remains unverified.
        // A label is never enough to claim Japanese-original voices.
    }
}
