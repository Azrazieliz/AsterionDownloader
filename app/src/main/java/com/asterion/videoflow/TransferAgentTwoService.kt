package com.asterion.videoflow

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Second real, independently scheduled Android process. This service never
 * scans site catalogs and uses the same persistent SQLite queue through
 * cross-process atomic claims. It has its own native Python runtime and cwd.
 */
class TransferAgentTwoService:Service(){
    override fun onBind(intent:Intent?):IBinder?=null

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        VideoEngine.makeChannel(this)
        startForeground(24015,
            VideoEngine.notification(this,"Second transfer agent ready"))
        val future=VideoEngine.submit(applicationContext,false){
            getSystemService(NotificationManager::class.java)
                .notify(24015,VideoEngine.notification(this,"Agent 2 · "+it.take(75)))
        }
        Thread({
            runCatching{future.get()}
            stopSelfResult(startId)
        },"videoflow-agent-two-lifecycle").start()
        return START_REDELIVER_INTENT
    }
}
