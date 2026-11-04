package com.azrael.pixivdumpsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED)return
        // Never block a boot broadcast on provider indexing or database reads.
        val pending=goAsync()
        kotlin.concurrent.thread(name="asterion-restore-download-schedules"){
            try{
                val app=context.applicationContext
                if(!AppPower.enabled(app))return@thread
                if(SessionStore.autoSync(app)){
                    Scheduler.ensure(app)
                    Scheduler.runSoon(app)
                }
                SourceIndex.ensure(app)
                CreatorArchiveScheduler.ensure(app)
                KoikatsuFollowing.ensure(app)
                RounderDispatch.ensure(app)
                DirectDownloadRetry.ensure(app)
                AsterionDownloadWorker.ensure(app)
                NovelEpubService.ensure(app)
                ChapterRecovery.ensure(app)
                XPhotoScheduler.ensure(app)
                NduCloudScheduler.configure(app,NduSettings.enabled(app))
                com.azrael.galleryflow.Scheduler.ensure(app)
                com.asterion.videoflow.Scheduling.ensure(app)
            }finally{pending.finish()}
        }
    }
}
