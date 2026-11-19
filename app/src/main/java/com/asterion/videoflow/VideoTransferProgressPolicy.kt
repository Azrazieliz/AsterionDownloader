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

    /**
     * The JK native VOD writer persists SHA-verified fragments in its private
     * selected-jk-* stage. The original top-level file scanner deliberately
     * does not count those nested fragments, so using it alone aborts a real
     * transfer as an 80-second zero-byte stall. Only a fresh, selected JK
     * journal stage can contribute these independently verified bytes.
     * Other video providers keep their existing top-level byte policy.
     */
    fun observedBytes(isJkNative:Boolean,topLevelBytes:Long,
                      journalStage:String?,journalUpdatedMs:Long?,
                      journalBytes:Long?,attemptStartWallMs:Long):Long {
        val disk=topLevelBytes.coerceAtLeast(0L)
        if(!isJkNative || journalStage !in setOf(
                "receiving","fragments","verifying") ||
            journalUpdatedMs==null ||
            journalUpdatedMs<attemptStartWallMs)return disk
        return maxOf(disk,journalBytes?.coerceAtLeast(0L)?:0L)
    }

    /** A complete original still needs ordered assembly and media validation.
     * A new selected-JK verification checkpoint is genuine work, even when
     * fragment bytes no longer increase; stale/repeated stage timestamps are
     * never considered forward progress.
     */
    fun nativeAssemblyAdvanced(isJkNative:Boolean,stage:String?,
                               updatedMs:Long?,lastTickMs:Long,
                               attemptStartMs:Long):Boolean =
        isJkNative && stage=="verifying" && updatedMs!=null &&
            updatedMs>=attemptStartMs && updatedMs>lastTickMs
}
