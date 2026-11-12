package com.azrael.pixivdumpsync

/** Unknown-length HTTP transfers may be receiving actual bytes even without
 * a percentage. Never present their progress as "connecting".
 */
internal object DownloadProgressPolicy {
    fun receiving(active:Boolean,bytes:Long,percent:Int?):Boolean =
        active && (bytes>0L || (percent?:0)>0)
}
