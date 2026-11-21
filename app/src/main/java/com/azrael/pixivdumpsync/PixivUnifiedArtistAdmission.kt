package com.azrael.pixivdumpsync

/** Pure admission rule. Do not initialize Android UI objects in JVM tests.
 * The native Kuroha foreground service already queues manual backfill.
 */
internal object PixivUnifiedArtistAdmission {
    fun permit(running:Boolean,mode:SyncMode):Boolean =
        !running || mode==SyncMode.BACKFILL
}
