package com.azrael.pixivdumpsync

/** Separate connected seeders (complete copies) from incomplete peers. */
internal object TorrentPeerDiagnostics {
    fun describe(percent:Int,rateKib:Long,peers:Int,seeders:Int,idleMs:Long):String {
        val seeds=seeders.coerceIn(0,peers.coerceAtLeast(0))
        val leechers=(peers-seeds).coerceAtLeast(0)
        val stalled=rateKib<=0L && idleMs>=90_000L
        return "$percent% · ${rateKib.coerceAtLeast(0)} KiB/s · "+
            "$seeds seeders · $leechers leechers"+
            (if(stalled)" · no piece progress" else "")
    }
}
