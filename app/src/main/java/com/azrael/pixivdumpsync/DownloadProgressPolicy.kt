package com.azrael.pixivdumpsync

/** Show transferring only after the active worker writes actual bytes. A
 * player/extractor's speculative percentage is NOT a receipt and must never
 * turn a connecting video into a supposedly downloading original.
 */
internal object DownloadProgressPolicy {
    fun receiving(active:Boolean,bytes:Long,
                  @Suppress("UNUSED_PARAMETER") percent:Int?):Boolean =
        active && bytes>0L
}
