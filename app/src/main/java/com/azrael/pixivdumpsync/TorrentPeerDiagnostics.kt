package com.azrael.pixivdumpsync

/** Distinguish real verified selected-piece bytes from tracker/peer traffic.
 * Peer connections and nonzero downloadRate do not establish file progress.
 */
internal object TorrentPeerDiagnostics {
    /** Piece checks can take minutes on large resumed files. Checking and
     * explicit pauses are not elapsed peer-stall time. Reset the baseline
     * if libtorrent invalidates old pieces during a recheck. */
    data class ProgressClock(val verifiedBytes:Long,val lastAdvanceMs:Long,
                             val reannounced:Boolean=false) {
        fun observe(nowMs:Long,verified:Long,checking:Boolean):ProgressClock =
            if(checking || verified!=verifiedBytes)
                ProgressClock(verified,nowMs)
            else this
        fun resume(nowMs:Long):ProgressClock = copy(lastAdvanceMs=nowMs,reannounced=false)
        fun idle(nowMs:Long):Long = (nowMs-lastAdvanceMs).coerceAtLeast(0L)
    }

    fun shouldReannounce(idleMs:Long,connectedSeeders:Int,
                         alreadyRequested:Boolean):Boolean =
        idleMs>=120_000L && !alreadyRequested

    fun describe(percent:Int,rateKib:Long,peers:Int,seeders:Int,idleMs:Long,
                 wantedDoneBytes:Long=0L,wantedTotalBytes:Long=0L,
                 payloadRateKib:Long=rateKib):String {
        val seeds=seeders.coerceIn(0,peers.coerceAtLeast(0))
        val leechers=(peers-seeds).coerceAtLeast(0)
        val checked=wantedDoneBytes.coerceAtLeast(0L)
        val total=wantedTotalBytes.coerceAtLeast(0L)
        val bytes=if(total>0L)
            " · verified ${checked/1024L/1024L}/${total/1024L/1024L} MiB"
            else ""
        val noProgress=idleMs>=90_000L
        return "${percent.coerceIn(0,100)}% · ${payloadRateKib.coerceAtLeast(0L)} KiB/s payload · "+
            "${seeds} seeders · ${leechers} leechers"+bytes+
            (if(noProgress)" · no verified piece progress" else "")
    }
}
