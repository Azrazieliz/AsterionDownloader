package com.azrael.pixivdumpsync

import android.app.DownloadManager

/** A system HTTP success is never proof of the requested original's bytes.
 * The cross-app bridge uses the same on-device file check as Downloads UI.
 */
internal object InterAppOriginalCompletionPolicy {
    fun resolve(systemStatus:Int, priorReceipt:String?,
                originalVerified:Boolean?):String? = when(systemStatus){
        DownloadManager.STATUS_SUCCESSFUL->
            if(priorReceipt !in setOf("INVALID","DISMISSED","STALLED") &&
                originalVerified==true)"COMPLETED" else "ERROR"
        DownloadManager.STATUS_FAILED->"ERROR"
        else->null
    }
}
