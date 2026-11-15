package com.azrael.pixivdumpsync

/**
 * Kuroha's separately installed historical inventory is OPTIONAL.
 *
 * Existing Pixiv artwork is checked individually against FileStore and any
 * verified Kuroha inventory entries before a file is downloaded. Missing SAF
 * permissions, a slow scan, or a stale inventory must never prevent Pixiv
 * itself from backfilling. Report reduced cross-app deduplication coverage
 * without turning an independent Pixiv network request into an error.
 */
internal object PixivLegacySyncGate {
    data class Assessment(val proceed:Boolean,val warning:String?)

    fun evaluate(mode:SyncMode,kurohaInstalled:Boolean,linkedFolders:Int,
                 scanning:Boolean,lastError:String?,refreshError:String?):Assessment {
        if(mode!=SyncMode.BACKFILL)return Assessment(true,null)
        val warning=when {
            !refreshError.isNullOrBlank()->"Kuroha inventory: "+refreshError
            kurohaInstalled && linkedFolders==0->
                "Kuroha originals folder is not linked; cross-app duplicates may not be detected"
            linkedFolders>0 && scanning->
                "Kuroha original-file inventory is still scanning"
            linkedFolders>0 && !lastError.isNullOrBlank()->lastError
            else->null
        }
        // No installed Kuroha package or inventory state may disable Pixiv.
        return Assessment(true,warning)
    }
}
