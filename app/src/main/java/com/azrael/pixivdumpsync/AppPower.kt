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
            Scheduler.cancel(context)
            XPhotoScheduler.cancel(context)
            com.azrael.galleryflow.Scheduler.cancel(context)
            com.asterion.videoflow.Scheduling.cancel(context)
            com.azrael.galleryflow.SyncControl.stop()
            SyncControl.stop()
            // A running upload has its own stop flag and is cooperatively cancelled.
            runCatching {
                context.startService(android.content.Intent(context,NduUploadService::class.java)
                    .setAction(NduUploadService.ACTION_STOP))
            }
            // Immediately stop service-owned backfill workers. The existing
            // queues and downloaded files remain intact for future resumption.
            for(service in listOf(
                com.azrael.galleryflow.SyncForegroundService::class.java,
                com.asterion.videoflow.TransferService::class.java,
                ChapterArchiveService::class.java,
                NduUploadService::class.java
            )){
                runCatching{
                    context.stopService(android.content.Intent(context,service))
                }
            }
            context.getSystemService(JobScheduler::class.java)?.let { jobs->
                // Work scheduled by components must not restart the app while OFF.
                // JobScheduler IDs are app-wide. Cancel all registered lanes;
                // retries and historical cursors remain safely persisted.
                for(id in listOf(
                    7751,7752,9811,28914,42681, // Pixiv/Kyora/X/VideoFlow
                    48531,48532,                 // SourceIndex
                    41082,41083,                 // Creator archives
                    48763,48872,                 // Rounder + large-library catch-up
                    48764,                       // Direct downloads
                    49867,49868,                 // Inter-app request intake
                    49862,                       // Koikatsu cards
                    49874,49875,                 // Novel EPUB chapters
                    28913,                       // Comic ZIP recovery
                    9321                         // Direct TeraBox retry
                ))jobs.cancel(id)
                // Catch future and already-installed job IDs as well: hardcoded
                // IDs previously omitted MP4Directs and torrent backfill.
                jobs.cancelAll()
            }
        }
    }
}
