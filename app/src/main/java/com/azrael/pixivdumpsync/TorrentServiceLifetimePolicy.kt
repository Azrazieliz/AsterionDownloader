package com.azrael.pixivdumpsync

/** Decides whether the isolated BitTorrent service can release its foreground
 * lifetime. A newer UI command is not allowed to tear down an older JNI
 * metadata request, selected transfer or quarantined export.
 */
internal object TorrentServiceLifetimePolicy {
    // Exports share private storage with the native session. Never overlap
    // disk-intensive export with native initialization, including cleanup.
    fun mayStartNative(transfers:Int,metadata:Int,exports:Int,cleanup:Int):Boolean =
        mayStop(transfers,metadata,exports,cleanup)

    fun mayStop(transfers:Int,metadata:Int,exports:Int,cleanup:Int=0):Boolean =
        transfers==0 && metadata==0 && exports==0 && cleanup==0
}
