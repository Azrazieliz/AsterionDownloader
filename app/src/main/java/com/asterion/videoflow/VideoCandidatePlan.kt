package com.asterion.videoflow

/** Try the selected source first; iframe discovery may be empty, stale, or
 * blocklisted by a publisher without invalidating the user's source URL.
 * Alternatives retain their original order, and duplicates never rerun.
 */
internal object VideoCandidatePlan {
    fun referer(selected:String,candidate:String):String? =
        selected.takeIf{it!=candidate}

    /** A configured, working per-site proxy permits publisher page discovery.
     * The legacy bare toggle without a relay must not send direct requests.
     */
    fun mayInspectPublisher(legacyProxy:Boolean,hidden:Boolean,
                            verifiedExitConfigured:Boolean):Boolean =
        !hidden && (!legacyProxy || verifiedExitConfigured)

    fun ordered(selected:String,providerAlternatives:List<String>,
                embeddedAlternatives:List<String>):List<String> {
        val preferred=EpornerOriginalSource.officialEmbed(selected)
        // Only Eporner uses the publisher-authorized embed FIRST: its
        // upstream watch-player hash parser is known broken. Every other
        // provider preserves the exact selected URL as its first candidate.
        return (listOfNotNull(preferred,selected)+
            providerAlternatives+embeddedAlternatives)
            .filter{it.isNotBlank()}.distinct()
    }
}
