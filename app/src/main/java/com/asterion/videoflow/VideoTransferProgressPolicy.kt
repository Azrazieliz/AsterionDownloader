package com.asterion.videoflow

/** Cross-process verified byte counts, never a speculative extractor percent. */
internal object VideoTransferProgressPolicy {
    fun description(percent:Int,verifiedBytes:Long):String = when {
        verifiedBytes>0L && percent>0->
            "$percent% reported · $verifiedBytes original bytes verified on disk"
        verifiedBytes>0L->"$verifiedBytes original bytes verified on disk"
        percent>0->"Extractor reports $percent% · checking for saved media bytes"
        else->"Waiting for first original media byte"
    }
}
