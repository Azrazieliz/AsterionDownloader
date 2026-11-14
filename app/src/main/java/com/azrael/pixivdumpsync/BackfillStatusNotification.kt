package com.azrael.pixivdumpsync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Persistent STOP access during generic JobScheduler backfill too.
 * The action is handled in :emergency if the main process is unresponsive.
 */
object BackfillStatusNotification {
    private const val CHANNEL="asterion_backfill_control"
    private const val ID=41987
    fun show(context:Context){
        if(!AppPower.enabled(context))return
        val app=context.applicationContext
        val manager=app.getSystemService(NotificationManager::class.java)
        if(Build.VERSION.SDK_INT>=33 &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=
                PackageManager.PERMISSION_GRANTED)return
        runCatching{
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL,"Backfill and sync controls",
                NotificationManager.IMPORTANCE_LOW))
            val open=PendingIntent.getActivity(app,41987,
                Intent(app,AsterionHomeActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop=EmergencyBackfillStopReceiver.action(app)
            val notification=Notification.Builder(app,CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Asterion Downloader · Backfill")
                .setContentText("Background sync enabled · stop without opening the app")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null,
                    "STOP BACKFILL",stop).build())
                .build()
            manager.notify(ID,notification)
        }
    }
    fun hide(context:Context){
        runCatching{
            context.getSystemService(NotificationManager::class.java).cancel(ID)
        }
    }
}
