package com.azrael.pixivdumpsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Foreground direct NDUS uploader. No Round Sync interaction. */
class NduUploadService : Service() {
    companion object {
        private const val CHANNEL="nocturne_ndus_cloud"
        private const val ID=193
        const val ACTION_UPLOAD="com.azrael.nocturne.ndus.UPLOAD"
        const val ACTION_MANUAL_UPLOAD="com.azrael.nocturne.ndus.MANUAL_UPLOAD"
        const val ACTION_STOP="com.azrael.nocturne.ndus.STOP"
        const val ACTION_QUEUE_EXISTING="com.azrael.nocturne.ndus.QUEUE_EXISTING"
        private val executor=Executors.newSingleThreadExecutor()
        @Volatile private var running:Future<*>?=null
        @Volatile private var stop=false
        @Volatile var currentStatus:String="Idle"
            private set
        fun launch(context:android.content.Context, backfill:Boolean=false) {
            if (!AppPower.enabled(context) || !NduSettings.configured(context)) return
            val i=Intent(context,NduUploadService::class.java)
                .setAction(if(backfill) ACTION_QUEUE_EXISTING else ACTION_UPLOAD)
            context.startForegroundService(i)
        }
        /** Explicit TeraBox upload; does not turn on site backfill/sync. */
        fun uploadNow(context:android.content.Context):Boolean{
            if(!NduSettings.configured(context))return false
            context.startForegroundService(Intent(context,NduUploadService::class.java)
                .setAction(ACTION_MANUAL_UPLOAD))
            return true
        }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"Nocturne TeraBox direct uploads",
                NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        // Every startForegroundService launch must promote before any early
        // return, including paused sync and an already active uploader.
        startForeground(ID,notification("Preparing direct TeraBox uploads"))
        if(intent?.action==ACTION_STOP) {
            stop=true;currentStatus="Upload stopping";running?.cancel(true)
            if(running?.isDone!=false) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
            return START_NOT_STICKY
        }
        val manual=intent?.action==ACTION_MANUAL_UPLOAD
        if(!manual && !AppPower.enabled(this)){
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if(running?.isDone==false) return START_NOT_STICKY
        stop=false
        running=executor.submit {
            try {
                if(!NduSettings.configured(this)) return@submit
                if(manual || intent?.action==ACTION_QUEUE_EXISTING) {
                    currentStatus="Scanning owned downloads across all modules"
                    notifyNow()
                    // Retain the legacy Kuroha/Kyora index and capture the unified
                    // Assets, Games, Books, Comics, Videos, X and Koikatsu folders.
                    NduCloudQueue(this).use { it.enqueueExisting(this) }
                    RounderDispatch.scan(this,manual=manual)
                }
                val outcome=NduUploadEngine.drain(this,20000,12*60_000L,
                    stop={stop || (!manual && !AppPower.enabled(this)) ||
                        Thread.currentThread().isInterrupted},
                    report={status->
                        currentStatus=status
                        notifyNow()
                    },
                    manual=manual)
                if(outcome.connectionError!=null) {
                    currentStatus="Direct upload: "+outcome.connectionError
                    notifyNow()
                }
            } catch(t:Throwable) {
                currentStatus="TeraBox connection failed: " +
                    (t.message ?: t.javaClass.simpleName)
                notifyNow()
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }
    private fun notification(text:String):Notification {
        val open=PendingIntent.getActivity(this,0,
            Intent(this,DownloaderHubActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val halt=EmergencyBackfillStopReceiver.action(this)
        return Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Nocturne / TeraBox")
            .setContentText(text.take(110))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null,"Stop",halt).build())
            .build()
    }
    private fun notifyNow() {
        getSystemService(NotificationManager::class.java)
            .notify(ID,notification(currentStatus))
    }
    override fun onBind(intent:Intent?):IBinder?=null
}
