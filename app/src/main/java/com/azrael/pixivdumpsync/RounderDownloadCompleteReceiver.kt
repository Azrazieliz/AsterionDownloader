package com.azrael.pixivdumpsync

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Rescan successful app-owned originals promptly for Rounder/TeraBox handoff.
 * A DownloadManager success is NOT a confirmation of TeraBox upload.
 */
class RounderDownloadCompleteReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent?){
        if(intent?.action!=DownloadManager.ACTION_DOWNLOAD_COMPLETE)return
        val id=intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-1L)
        if(id<=0)return
        val app=context.applicationContext
        if(!AppPower.enabled(app))return
        val pending=goAsync()
        kotlin.concurrent.thread(name="asterion-rounder-download-complete"){
            try{
                val known=DirectDownloadTracker.states(app,250)
                    .any{it.job.id==id &&
                        it.status==DownloadManager.STATUS_SUCCESSFUL}
                if(known)RounderDispatch.scan(app)
            }finally{pending.finish()}
        }
    }
}
