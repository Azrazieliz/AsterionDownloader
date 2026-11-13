package com.asterion.videoflow

/** Try the selected source first; iframe discovery may be empty, stale, or
 * blocklisted by a publisher without invalidating the user's source URL.
 * Alternatives retain their original order, and duplicates never rerun.
 */
internal object VideoCandidatePlan {
    fun referer(selected:String,candidate:String):String? =
        selected.takeIf{it!=candidate}

    fun ordered(selected:String,providerAlternatives:List<String>,
                embeddedAlternatives:List<String>):List<String> =
        (listOf(selected)+providerAlternatives+embeddedAlternatives)
            .filter{it.isNotBlank()}.distinct()
}
