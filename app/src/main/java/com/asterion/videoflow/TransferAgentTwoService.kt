package com.asterion.videoflow

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Secondary Android process, independently claimed VideoDb rows.
 * Foreground lifecycle ends even when an embedded extractor stops responding.
 */
class TransferAgentTwoService:Service(){
    @Volatile private var active:java.util.concurrent.Future<*>?=null
    @Volatile private var timedOut=false
    override fun onBind(intent:Intent?):IBinder?=null

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(timedOut){stopSelf();return START_NOT_STICKY}
        try{
            VideoEngine.makeChannel(this)
            startForeground(24015,
                VideoEngine.notification(this,"Second transfer agent ready"))
        }catch(t:Exception){
            runCatching{Scheduling.requestSecondaryImmediate(applicationContext)}
            stopSelf()
            return START_NOT_STICKY
        }
        if(active?.isDone==false)return START_NOT_STICKY
        val future=VideoEngine.submit(applicationContext,false){
            if(!timedOut)runCatching{
                getSystemService(NotificationManager::class.java)
                    .notify(24015,VideoEngine.notification(this,"Agent 2 · "+it.take(75)))
            }
        }
        active=future
        Thread({
            try{future.get()}catch(_:Exception){}
            finally{
                if(active===future)active=null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        },"videoflow-agent-two-lifecycle").start()
        return START_NOT_STICKY
    }
    override fun onTimeout(startId:Int,fgsType:Int){
        timedOut=true
        active?.cancel(true)
        active=null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        runCatching{Scheduling.requestRetry(applicationContext,4L*60_000L)}
    }
    override fun onDestroy(){
        active?.cancel(true)
        active=null
        super.onDestroy()
    }
}
