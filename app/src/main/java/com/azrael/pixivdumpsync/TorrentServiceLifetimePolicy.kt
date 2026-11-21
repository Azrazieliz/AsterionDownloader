package com.azrael.pixivdumpsync

/** Decides whether the isolated BitTorrent service can release its foreground
 * lifetime. A newer UI command is not allowed to tear down an older JNI
 * metadata request, selected transfer or quarantined export.
 */
internal object TorrentServiceLifetimePolicy {
    fun mayStop(transfers:Int,metadata:Int,exports:Int):Boolean =
        transfers==0 && metadata==0 && exports==0
}
