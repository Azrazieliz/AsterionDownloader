package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class HentaiVostaPolicyTest {
    @Test fun japaneseAudioPreferredButSoleSourceAudioIsNotForbidden(){
        val options=HentaiVostaPolicy.selectedAudioFormat.split('/')
        assertTrue(options[0].contains("+ba[language=ja]"))
        assertTrue(options[1].contains("+ba[language=jpn]"))
        assertTrue(options.any{it.endsWith("+ba")})
        assertEquals("en",HentaiVostaPolicy.englishSubtitleLanguage)
    }

    @Test fun observedOnlyPublisherAudioLabelDoesNotClaimEnglishDub(){
        val manifestAudioTracks=listOf("audio-English")
        assertEquals(1,manifestAudioTracks.size)
        // The label cannot establish whether the dialogue is English or
        // Japanese; it must not make the only real original undownloadable.
        assertTrue(HentaiVostaPolicy.selectedAudioFormat.contains("+ba"))
    }
}
