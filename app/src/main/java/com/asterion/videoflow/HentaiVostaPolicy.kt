package com.asterion.videoflow

/** VOSTA acceptance: Japanese original voice + English subtitles.
 *
 * A rendition called "audio-English" is NOT evidence of Japanese voices.
 * Never fall back to an English-dub-labelled rendition or generic 'ba'.
 * Without a publisher-declared original Japanese track the extractor
 * must stop rather than claim a VOSTA original. Actual speech and final
 * embedded subtitle verification remain separate release requirements.
 */
internal object HentaiVostaPolicy {
    const val selectedAudioFormat =
        "bv[height<=480]+ba[language=ja]/" +
        "bv[height<=480]+ba[language=jpn]/" +
        "bv+ba[language=ja]/" +
        "bv+ba[language=jpn]"
    const val englishSubtitleLanguage="en"
}
