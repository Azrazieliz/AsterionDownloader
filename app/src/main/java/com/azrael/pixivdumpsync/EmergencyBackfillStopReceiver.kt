package com.azrael.pixivdumpsync

import android.app.ActivityManager
import android.app.JobScheduler
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import kotlin.concurrent.thread

/**
 * Emergency global STOP, isolated from the main UI process. A notification
 * action must remain functional when a large backfill stalls the app screen.
 * Persists OFF before killing only this app's main process. Does not delete
 * downloads, databases, historical cursors, subscriptions, or other apps.
 */
class EmergencyBackfillStopReceiver:BroadcastReceiver(){
    companion object {
        const val ACTION_STOP_ALL="com.azrael.pixivdumpsync.STOP_ALL_BACKFILL"
        fun action(context:Context):PendingIntent =
            PendingIntent.getBroadcast(context,47191,
                Intent(context,EmergencyBackfillStopReceiver::class.java)
                    .setAction(ACTION_STOP_ALL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onReceive(context:Context,intent:Intent?){
        if(intent?.action!=ACTION_STOP_ALL)return
        val result=goAsync()
        val app=context.applicationContext
        thread(name="asterion-emergency-notification-stop"){
            try {
                // The receiver runs in :emergency, so it remains responsive
                // even if the main renderer or download engine is blocked.
                app.getSharedPreferences("asterion_master_power",Context.MODE_PRIVATE)
                    .edit().putBoolean("enabled",false).commit()
                runCatching{
                    app.getSystemService(JobScheduler::class.java).cancelAll()
                }
                for(service in listOf(
                    com.azrael.galleryflow.SyncForegroundService::class.java,
                    com.asterion.videoflow.TransferService::class.java,
                    ChapterArchiveService::class.java,
                    NduUploadService::class.java
                ))runCatching{app.stopService(Intent(app,service))}
                // Android permits processes sharing this app UID to stop each
                // other. Never target other package names or the control lane.
                val manager=app.getSystemService(ActivityManager::class.java)
                manager.runningAppProcesses.orEmpty().forEach { process ->
                    if(process.uid==Process.myUid() &&
                        process.processName==app.packageName &&
                        process.pid!=Process.myPid()) {
                        runCatching{Process.killProcess(process.pid)}
                    }
                }
            }finally{result.finish()}
        }
    }
}
