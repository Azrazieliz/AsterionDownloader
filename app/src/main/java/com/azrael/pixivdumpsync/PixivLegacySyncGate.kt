package com.azrael.pixivdumpsync

/** Never block a normal Pixiv live update or a cloud queue on unrelated
 * legacy Kuroha folder access. Only historical replay risks duplicates.
 */
internal object PixivLegacySyncGate {
    fun reason(mode:SyncMode,kurohaInstalled:Boolean,linkedFolders:Int,
               scanning:Boolean,lastError:String?,refreshError:String?):String? {
        if(mode!=SyncMode.BACKFILL)return null
        if(!refreshError.isNullOrBlank())return "Kuroha inventory: "+refreshError
        if(kurohaInstalled && linkedFolders==0)
            return "Kuroha originals need one-time folder access before backfill"
        if(linkedFolders>0 && scanning)
            return "Kuroha original-file inventory is still scanning"
        if(linkedFolders>0 && !lastError.isNullOrBlank())return lastError
        return null
    }
}
