package com.azrael.galleryflow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.util.concurrent.Executors
import java.util.concurrent.Future

class SyncForegroundService : Service() {
    companion object {
        const val ACTION_START = "com.azrael.galleryflow.START"
        const val ACTION_PAUSE = "com.azrael.galleryflow.PAUSE"
        const val ACTION_RESUME = "com.azrael.galleryflow.RESUME"
        const val ACTION_STOP = "com.azrael.galleryflow.STOP"
        const val ACTION_RECOVER = "com.azrael.galleryflow.RECOVER_SELECTED"
        const val EXTRA_MODE = "sync_mode"
        const val EXTRA_SITE_ID = "target_site_id"
        const val EXTRA_SITE_HOST = "target_site_host"
        const val EXTRA_ALBUM_URL = "target_album_url"
        const val EXTRA_ALBUM_TITLE = "target_album_title"
        private const val CHANNEL_ID = "galleryflow_sync"
        private const val NOTIFICATION_ID = 91
    }

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var currentRun: Future<*>? = null
    @Volatile private var activeRequest:GalleryRequestLedger.Request?=null
    private val pending=ArrayDeque<GalleryRequestLedger.Request>()

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "GalleryFlow sync", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A scheduled background restart can be denied foreground-service
        // promotion by Android. Preserve the durable queue and return safely:
        // an uncaught exception here used to kill the whole app in background.
        try {
            startForeground(NOTIFICATION_ID, notification())
        }catch(t:RuntimeException){
            android.util.Log.e("AsterionGallerySync",
                "Foreground promotion denied; saved gallery jobs retained",t)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        when(intent?.action){
            ACTION_PAUSE->{SyncControl.pause();notifyNow();return START_NOT_STICKY}
            ACTION_RESUME->{SyncControl.resume();notifyNow();return START_NOT_STICKY}
            ACTION_STOP->{
                synchronized(pending){pending.clear()}
                GalleryRequestLedger.clear(this)
                SyncControl.stop()
                currentRun?.cancel(true)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
        }
        if(!com.azrael.pixivdumpsync.AppPower.enabled(this)){
            synchronized(pending){pending.clear()}
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        val mode=runCatching{
            SyncMode.valueOf(intent?.getStringExtra(EXTRA_MODE)?:SyncMode.LIVE.name)
        }.getOrDefault(SyncMode.LIVE)
        val requested=if(intent?.action==ACTION_RECOVER)null
            else GalleryRequestLedger.Request(
                mode,intent?.getStringExtra(EXTRA_SITE_ID),
                intent?.getStringExtra(EXTRA_SITE_HOST),
                intent?.getStringExtra(EXTRA_ALBUM_URL),
                intent?.getStringExtra(EXTRA_ALBUM_TITLE))
        if(requested?.targeted==true)GalleryRequestLedger.remember(this,requested)
        // Recover unfinished album taps first. An untargeted automatic Live
        // run is not persisted and cannot turn an opted-out Sync back on.
        val jobs=(GalleryRequestLedger.pending(this)+listOfNotNull(requested))
            .distinctBy{it.signature()}
        synchronized(pending){
            if(currentRun?.isDone==false){
                for(job in jobs){
                    if(job!=activeRequest && job !in pending)pending.addLast(job)
                }
                notifyNow()
                return START_NOT_STICKY
            }
            if(jobs.isEmpty()){
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            pending.clear()
            pending.addAll(jobs.drop(1))
            activeRequest=jobs.first()
            currentRun=executor.submit{
                try{
                    var next:GalleryRequestLedger.Request?=jobs.first()
                    while(next!=null &&
                        com.azrael.pixivdumpsync.AppPower.enabled(applicationContext)){
                        val job=next
                        if(job!=null){
                            SyncEngine(applicationContext).run(job.mode,
                                targetEntityId=job.entityId,
                                targetSiteHost=job.siteHost,
                                targetGalleryUrl=job.albumUrl,
                                targetGalleryTitle=job.albumTitle){notifyNow()}
                            // Only remove after the engine has returned.
                            // Failed originals remain in GalleryDb retry rows.
                            GalleryRequestLedger.completed(applicationContext,job)
                        }
                        next=synchronized(pending){
                            val following=if(pending.isEmpty())null
                                else pending.removeFirst()
                            activeRequest=following
                            following
                        }
                    }
                }finally{
                    synchronized(pending){
                        pending.clear()
                        activeRequest=null
                        currentRun=null
                    }
                    runCatching{notifyNow()}
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    getSystemService(NotificationManager::class.java)
                        .cancel(NOTIFICATION_ID)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+ will crash dataSync services that exceed the foreground
     * quota unless they terminate promptly. Preserve already downloaded
     * originals and database retry rows; interrupt the current request.
     */
    override fun onTimeout(startId:Int,fgsType:Int){
        SyncControl.stop()
        currentRun?.cancel(true)
        synchronized(pending){pending.clear()}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy(){
        synchronized(pending){pending.clear()}
        currentRun?.cancel(true)
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notifyNow() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val s = SyncControl.snapshot()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleAction = if (s.paused) ACTION_RESUME else ACTION_PAUSE
        val toggleLabel = if (s.paused) "Resume" else "Pause"
        val toggle = PendingIntent.getService(
            this, 1, Intent(this, SyncForegroundService::class.java).setAction(toggleAction), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = com.azrael.pixivdumpsync.EmergencyBackfillStopReceiver.action(this)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Asterion · Kyora")
            .setContentText(s.message.take(180))
            .setContentIntent(open)
            .setOngoing(s.running)
            .addAction(Notification.Action.Builder(null, toggleLabel, toggle).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }
}
