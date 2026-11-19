package com.azrael.pixivdumpsync

import android.app.DownloadManager

/** A failed DownloadManager request may be acknowledged but never a live,
 * queued, validly saved or paused original. Clearing is not file deletion. */
internal object DirectDownloadClearPolicy {
    fun mayAcknowledge(status:Int,receipt:String):Boolean =
        status==DownloadManager.STATUS_FAILED ||
            (status==DownloadManager.STATUS_SUCCESSFUL && receipt=="INVALID")
}
