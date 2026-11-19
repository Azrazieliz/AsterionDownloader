package com.asterion.videoflow

/** VOSTA means original Japanese voices with English subtitles.
 * HentaiHaven's actual selected HLS publishes exactly one audio rendition,
 * labelled "audio-English", despite the owner confirming that no English
 * dub exists. A metadata label alone does not identify spoken dialogue.
 *
 * Prefer explicitly Japanese-labelled audio if it exists; otherwise retain
 * the source's own audio. Final language/subtitle verification is separate
 * from the media-transfer receipt and must never be falsely asserted.
 */
internal object HentaiVostaPolicy {
    const val selectedAudioFormat =
        "bv[height<=480]+ba[language=ja]/" +
        "bv[height<=480]+ba[language=jpn]/" +
        "bv[height<=480]+ba/bv+ba"
    const val englishSubtitleLanguage="en"
}
