package com.azrael.pixivdumpsync

import android.app.job.JobScheduler
import android.content.Context

/** One persistent master switch, independent of all artist and site preferences.
 * OFF prevents scheduled and manual background networking, preserving all queues. */
object AppPower {
    private const val NAME="asterion_master_power"
    fun enabled(context:Context):Boolean =
        context.getSharedPreferences(NAME,Context.MODE_PRIVATE).getBoolean("enabled",true)

    fun set(context:Context,value:Boolean) {
        context.getSharedPreferences(NAME,Context.MODE_PRIVATE).edit()
            .putBoolean("enabled",value).commit()
        if(value){
            // Scheduling and database initialization must never run on the UI
            // thread when the user resumes a large existing library.
            val app=context.applicationContext
            kotlin.concurrent.thread(name="asterion-resume-schedulers"){
                if(!enabled(app))return@thread
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_BACKGROUND)
                // On paused startup these operations are skipped. Resume
                // initializes them here so no source is silently lost.
                runCatching{
                    com.azrael.galleryflow.GalleryDb(app).use{
                        it.seedCatalogOnce(app)
                    }
                }
                if(enabled(app))runCatching{AlohaDefaults.ensure(app)}
                if(enabled(app))runCatching{CreatorNames.seed(app)}
                if(!enabled(app))return@thread
                runCatching{Scheduler.ensure(app)}
                runCatching{XPhotoScheduler.ensure(app)}
                runCatching{com.azrael.galleryflow.Scheduler.ensure(app)}
                runCatching{com.asterion.videoflow.Scheduling.ensure(app)}
                runCatching{SourceIndex.ensure(app)}
                runCatching{CreatorArchiveScheduler.ensure(app)}
                runCatching{KoikatsuFollowing.ensure(app)}
                runCatching{RounderDispatch.ensure(app)}
                runCatching{Mp4SeriesSync.ensure(app)}
                runCatching{TorrentSourceBackfill.ensure(app)}
                runCatching{DirectDownloadRetry.ensure(app)}
                runCatching{AsterionDownloadWorker.ensure(app)}
                runCatching{NovelEpubService.ensure(app)}
                runCatching{ChapterRecovery.ensure(app)}
                runCatching{NduCloudScheduler.configure(app,NduSettings.enabled(app))}
                if(enabled(app))RounderDispatch.scanAsync(app)
            }
        }else{
            // Persist the OFF state above before doing any cancellation.
            // Crucially, never block a frozen UI on JobScheduler, service
            // teardown or the TeraBox uploader. All workers consult enabled().
            com.azrael.galleryflow.SyncControl.stop()
            SyncControl.stop()
            val app=context.applicationContext
            kotlin.concurrent.thread(name="asterion-emergency-stop",isDaemon=true){
                if(enabled(app))return@thread
                runCatching{Scheduler.cancel(app)}
                runCatching{XPhotoScheduler.cancel(app)}
                runCatching{com.azrael.galleryflow.Scheduler.cancel(app)}
                runCatching{com.asterion.videoflow.Scheduling.cancel(app)}
                runCatching{
                    app.startService(android.content.Intent(app,NduUploadService::class.java)
                        .setAction(NduUploadService.ACTION_STOP))
                }
                // Do not delete any downloaded files, subscriptions, or queues.
                for(service in listOf(
                    com.azrael.galleryflow.SyncForegroundService::class.java,
                    com.asterion.videoflow.TransferService::class.java,
                    ChapterArchiveService::class.java,
                    NduUploadService::class.java
                )){
                    runCatching{app.stopService(android.content.Intent(app,service))}
                }
                runCatching{app.getSystemService(JobScheduler::class.java)?.cancelAll()}
            }
        }
    }
}
