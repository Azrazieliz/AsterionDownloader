package com.azrael.galleryflow

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JobScheduler already grants a bounded background execution window for
 * scheduled gallery Live sync. Android 12+ must not start a dataSync
 * foreground service from an ordinary background JobService invocation.
 *
 * The previous startForegroundService() in onStartJob crashed the entire
 * application on Android 17 (ForegroundServiceStartNotAllowedException).
 * Run the scheduled work inside this JobService and call jobFinished only
 * after the worker ends. User-initiated sync still uses the notification
 * foreground service; manual requests and retry receipts are unchanged.
 */
class SyncJobService : JobService() {
    private val executor=Executors.newSingleThreadExecutor()
    private val active=AtomicBoolean(false)
    @Volatile private var worker:Future<*>?=null
    @Volatile private var stopped=false

    override fun onStartJob(params:JobParameters?):Boolean {
        if(params==null ||
            !com.azrael.pixivdumpsync.AppPower.enabled(this) ||
            !Prefs.autoSync(this) ||
            SyncControl.snapshot().running ||
            !active.compareAndSet(false,true))return false
        stopped=false
        return try{
            worker=executor.submit {
                try{
                    if(!stopped && com.azrael.pixivdumpsync.AppPower.enabled(
                            applicationContext) && Prefs.autoSync(applicationContext)) {
                        SyncEngine(applicationContext).run(SyncMode.LIVE)
                    }
                }catch(t:Throwable){
                    Log.e("AsterionGalleryJob","Scheduled gallery sync failed",t)
                }finally{
                    active.set(false)
                    if(!stopped){
                        // JobScheduler owns the background lifetime; do not
                        // fabricate a foreground service launch from here.
                        jobFinished(params,false)
                    }
                }
            }
            true
        }catch(t:RuntimeException){
            active.set(false)
            Log.e("AsterionGalleryJob","Could not schedule gallery job",t)
            false
        }
    }

    override fun onStopJob(params:JobParameters?):Boolean {
        stopped=true
        if(active.get()){
            SyncControl.stop()
            worker?.cancel(true)
        }
        return com.azrael.pixivdumpsync.AppPower.enabled(this) &&
            Prefs.autoSync(this)
    }

    override fun onDestroy(){
        stopped=true
        worker?.cancel(true)
        executor.shutdownNow()
        super.onDestroy()
    }
}
