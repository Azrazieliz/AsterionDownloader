package com.azrael.pixivdumpsync

import android.app.job.JobService
import android.app.job.JobParameters

/** One-shot fallback when Android denies a background FGS start.
 * Does not reset the cursor, cache, queue or previous chapter attempts.
 */
class ChapterArchiveJobService:JobService(){
    override fun onStartJob(params:JobParameters?):Boolean{
        if(!AppPower.enabled(this))return false
        val app=applicationContext
        val future=ChapterArchives.run(app){}
        Thread{
            try{runCatching{future.get()}}
            finally{
                jobFinished(params,false)
                if(AppPower.enabled(app)){
                    val pending=runCatching{ChapterQueue(app).use{it.next()!=null}}
                        .getOrDefault(false)
                    if(pending)ChapterArchives.schedule(app)
                }
            }
        }.start()
        return true
    }
    override fun onStopJob(params:JobParameters?):Boolean=true
}
