package com.azrael.pixivdumpsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Manual backfill can ask an active Live pass to yield at existing checkpoints. */
internal object PixivManualBackfillPriority {
    fun shouldPreempt(running:Boolean,mode:SyncMode?,stopping:Boolean):Boolean =
        running && mode==SyncMode.LIVE && !stopping
}

class SyncForegroundService : Service() {
    companion object {
        const val ACTION_START = "com.azrael.pixivdumpsync.START"
        const val ACTION_PAUSE = "com.azrael.pixivdumpsync.PAUSE"
        const val ACTION_RESUME = "com.azrael.pixivdumpsync.RESUME"
        const val ACTION_STOP = "com.azrael.pixivdumpsync.STOP"
        const val EXTRA_MODE = "sync_mode"
        const val EXTRA_SELECTED_ONLY = "selected_only"
        const val EXTRA_TARGET_ARTIST_ID = "target_artist_id"
        const val EXTRA_TARGET_ARTWORK_ID = "target_artwork_id"
        const val EXTRA_FEED_ONLY = "feed_only"
        const val EXTRA_AUTO_TRIGGERED = "auto_triggered"

        private const val CHANNEL_ID = "kuroha_sync"
        private const val NOTIFICATION_ID = 41
    }

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var currentRun: Future<*>? = null
    @Volatile private var lastNotificationAt = 0L
    private val mainHandler=Handler(Looper.getMainLooper())
    private val deferredLock=Any()
    private val deferredPrefs="asterion_pixiv_manual_backfill_v1"
    private val deferredKey="queued_requests"

    /** Manual history requests are never silently dropped by an active Live run.
     * Persist the FIFO across service restarts without resetting artist cursors.
     */
    private fun enqueueBackfill(selectedOnly:Boolean,artist:String?):Int =
        synchronized(deferredLock){
            val prefs=getSharedPreferences(deferredPrefs,MODE_PRIVATE)
            val queue=runCatching{JSONArray(prefs.getString(deferredKey,"[]"))}
                .getOrDefault(JSONArray())
            for(i in 0 until queue.length()){
                val it=queue.optJSONObject(i)?:continue
                if(it.optBoolean("selected_only")==selectedOnly &&
                    it.optString("artist")==artist.orEmpty())return@synchronized queue.length()
            }
            check(queue.length()<48){"Too many pending Pixiv backfill requests"}
            queue.put(JSONObject().put("selected_only",selectedOnly)
                .put("artist",artist.orEmpty()).put("requested_ms",System.currentTimeMillis()))
            check(prefs.edit().putString(deferredKey,queue.toString()).commit()){
                "Cannot save deferred Pixiv backfill"
            }
            queue.length()
        }

    private fun popBackfill():Intent?=synchronized(deferredLock){
        val prefs=getSharedPreferences(deferredPrefs,MODE_PRIVATE)
        val queue=runCatching{JSONArray(prefs.getString(deferredKey,"[]"))}
            .getOrDefault(JSONArray())
        if(queue.length()==0)return@synchronized null
        val next=queue.optJSONObject(0)?:return@synchronized null
        val remaining=JSONArray()
        for(i in 1 until queue.length())remaining.put(queue.get(i))
        if(!prefs.edit().putString(deferredKey,remaining.toString()).commit())
            return@synchronized null
        Intent(this,SyncForegroundService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_MODE,SyncMode.BACKFILL.name)
            .putExtra(EXTRA_SELECTED_ONLY,next.optBoolean("selected_only",true))
            .apply{
                next.optString("artist").takeIf{it.isNotBlank()}
                    ?.let{putExtra(EXTRA_TARGET_ARTIST_ID,it)}
            }
    }
    private fun clearDeferredBackfills(){
        synchronized(deferredLock){
            getSharedPreferences(deferredPrefs,MODE_PRIVATE).edit()
                .remove(deferredKey).commit()
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Kuroha sync",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground-service promotion may be denied when a job resumes
        // after the user backgrounds the app. Never propagate that runtime
        // exception from onStartCommand and kill the whole application.
        try {
            startForeground(NOTIFICATION_ID, notification())
        }catch(t:RuntimeException){
            android.util.Log.e("AsterionKurohaSync",
                "Foreground promotion denied; saved jobs retained",t)
            // This may be a SECOND command to an already-running Live or
            // Backfill worker. stopSelfResult(latest startId) would tear down
            // that worker's service and silently lose the selected request.
            if(!PixivForegroundLifetimePolicy.keepExistingWorker(
                    currentRun?.isDone==false)){
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            // The existing foreground worker is still alive. Process
            // pause/stop/queued-backfill controls without killing its service.
        }
        when (intent?.action) {
            ACTION_PAUSE -> {
                SyncControl.pause()
                updateNotification(force = true)
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                SyncControl.resume()
                updateNotification(force = true)
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                clearDeferredBackfills()
                SyncControl.stop()
                currentRun?.cancel(true)
                updateNotification(force = true)
                if (currentRun == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }

        if(!AppPower.enabled(this)){
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val mode = runCatching {
            SyncMode.valueOf(intent?.getStringExtra(EXTRA_MODE) ?: SyncMode.LIVE.name)
        }.getOrDefault(SyncMode.LIVE)
        val selectedOnly = intent?.getBooleanExtra(EXTRA_SELECTED_ONLY, false) ?: false
        val targetArtistId = intent?.getStringExtra(EXTRA_TARGET_ARTIST_ID)
        val targetArtworkId = intent?.getStringExtra(EXTRA_TARGET_ARTWORK_ID)
        val feedOnly = intent?.getBooleanExtra(EXTRA_FEED_ONLY, false) ?: false
        val autoTriggered = intent?.getBooleanExtra(EXTRA_AUTO_TRIGGERED, false) ?: false

        if (currentRun?.isDone == false) {
            if(mode==SyncMode.BACKFILL){
                val size=runCatching{enqueueBackfill(selectedOnly,targetArtistId)}
                size.onSuccess{position->
                    val current=SyncControl.snapshot()
                    if(PixivManualBackfillPriority.shouldPreempt(
                            current.running,current.mode,current.stopping)){
                        // The manual request is durably queued first. Then
                        // Live yields through its normal checkpoints. Do not
                        // cancel the Future: it can claim isDone before the
                        // worker releases the original Pixiv database lease.
                        SyncControl.stop()
                        SyncControl.updateMessage("Pixiv manual backfill prioritized · "+
                            position+" queued; finishing current Live request")
                    }else{
                        SyncControl.updateMessage("Pixiv backfill queued behind active "+
                            (current.mode?.name?:"sync")+" · "+position+
                            " manual request(s) pending")
                    }
                }.onFailure{failure->
                    android.util.Log.e("AsterionPixivBackfill",
                        "Could not persist manual request",failure)
                    SyncControl.updateMessage("Pixiv backfill queue rejected · "+
                        (failure.message?:failure.javaClass.simpleName).take(90))
                }
                updateNotification(force=true)
            }
            return START_NOT_STICKY
        }

        currentRun = executor.submit {
            try {
                if (autoTriggered) {
                    SessionStore.setLastAutoSyncResult(applicationContext, "Running")
                }
                val stats = SyncEngine(applicationContext).run(
                    mode = mode,
                    selectedOnly = selectedOnly,
                    targetArtistId = targetArtistId,
                    targetArtworkId = targetArtworkId,
                    feedOnly = feedOnly
                ) {
                    updateNotification()
                }
                if (autoTriggered) {
                    SessionStore.setLastAutoSyncResult(
                        applicationContext,
                        if (stats.errors > 0) {
                            "Finished with ${stats.errors} error(s)"
                        } else {
                            "OK • ${stats.worksCompleted} downloaded • ${stats.skippedDone} already done"
                        }
                    )
                }
            } catch (t: Throwable) {
                if (autoTriggered) {
                    SessionStore.setLastAutoSyncResult(
                        applicationContext,
                        "Failed • ${t.message ?: t.javaClass.simpleName}"
                    )
                }
                val current = SyncControl.snapshot()
                if (current.running) {
                    if (
                        current.stopping ||
                        t is SyncCancelledException ||
                        t is InterruptedException
                    ) {
                        SyncControl.forceFinish("Stopped")
                    } else {
                        SyncControl.forceFinish(
                            "Sync failed: ${t.message ?: t.javaClass.simpleName}"
                        )
                    }
                }
                updateNotification(force = true)
            } finally {
                if (SyncControl.snapshot().running) {
                    val current = SyncControl.snapshot()
                    SyncControl.forceFinish(if (current.stopping) "Stopped" else "Idle")
                }
                updateNotification(force = true)
                // The next owner-requested Backfill has priority over a new
                // periodic JobScheduler pass and must not wait for the OS to
                // start a second foreground service minutes later.
                mainHandler.post {
                    currentRun=null
                    val next=if(AppPower.enabled(this))popBackfill() else null
                    if(next!=null){
                        onStartCommand(next,0,startId)
                    }else{
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        getSystemService(NotificationManager::class.java)
                            .cancel(NOTIFICATION_ID)
                        // A second control/start command can advance Android's
                        // startId while this original worker is still active.
                        // All queued work is now drained and currentRun=null;
                        // stop the actual idle Service, not an obsolete ID.
                        stopSelf()
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun updateNotification(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationAt < 750L) return
        lastNotificationAt = now
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val snapshot = SyncControl.snapshot()

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, AsterionHomeActivity::class.java)
                .putExtra("asterion_page","Pixiv"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleAction = if (snapshot.paused) ACTION_RESUME else ACTION_PAUSE
        val toggleLabel = if (snapshot.paused) "Resume" else "Pause"
        val toggleIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SyncForegroundService::class.java).setAction(toggleAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = EmergencyBackfillStopReceiver.action(this)

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Asterion · Kuroha")
            .setContentText(snapshot.message.take(180))
            .setContentIntent(open)
            .setOngoing(snapshot.running)
            .addAction(
                Notification.Action.Builder(
                    null,
                    toggleLabel,
                    toggleIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Stop",
                    stopIntent
                ).build()
            )
            .build()
    }
}
