package com.azrael.pixivdumpsync

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
            stopSelfResult(startId)
            return START_NOT_STICKY
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
                stopForeground(STOP_FOREGROUND_REMOVE)
                getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                currentRun = null
                stopSelf()
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
            Intent(this, MainActivity::class.java),
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
