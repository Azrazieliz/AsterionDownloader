package com.azrael.pixivdumpsync

import android.app.DownloadManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * Explicitly ordered releases: only one volume of a given work may transfer
 * at a time. Completion of an Android download *request* is not completion of
 * a usable EPUB/ZIP. Unrelated works proceed independently.
 *
 * Do not apply this scheduler to photo albums, artwork or loose images.
 */
internal object Mp4VolumeDispatcher {
    private const val JOB_ID=49898

    private fun verified(context:Context,uri:Uri,type:String):Boolean =
        runCatching{
            context.contentResolver.openInputStream(uri)?.use{input->
                ArchiveIntegrity.inspect(input,type)
                true
            }==true
        }.getOrDefault(false)

    fun dispatch(context:Context,db:Mp4SeriesStore,work:Mp4Follow):Boolean {
        val candidates=db.orderedCandidates(work.id,work.type)
            .filter{it.volume>=work.firstVolume}
            .groupBy{it.volume}.toSortedMap()
        if(candidates.isEmpty())return false
        val manager=context.getSystemService(DownloadManager::class.java)
        var expected=work.firstVolume
        for((number,options) in candidates){
            // Do not invent a missing volume or download volume 9 before 8.
            if(number!=expected)return false
            val stored=options.firstOrNull{it.state=="PRESENT"}
            if(stored!=null || RounderInventoryIndex.isConfirmedPresent(
                    context,work.name,number,work.type)){
                if(stored==null)db.mark(options.first().url,"PRESENT")
                expected++
                continue
            }
            val active=options.firstOrNull{it.state=="QUEUED"}
            if(active!=null){
                val id=active.downloadId
                if(id==null || id<=0L){
                    // Historical records with no request ID are unverified.
                    db.mark(active.url,"FAILED")
                    return false
                }
                var status=DownloadManager.STATUS_FAILED
                manager.query(DownloadManager.Query()
                    .setFilterById(id))?.use{cursor->
                    if(cursor.moveToFirst()){
                        val column=cursor.getColumnIndex(
                            DownloadManager.COLUMN_STATUS)
                        if(column>=0)status=cursor.getInt(column)
                    }
                }
                when(status){
                    DownloadManager.STATUS_SUCCESSFUL->{
                        val uri=manager.getUriForDownloadedFile(id)
                        if(uri==null || !verified(context,uri,work.type)){
                            db.mark(active.url,"FAILED",id)
                            return false
                        }
                        db.mark(active.url,"PRESENT",id)
                        expected++
                        continue
                    }
                    DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_RUNNING,
                    DownloadManager.STATUS_PAUSED->{
                        schedule(context)
                        return true
                    }
                    else->{
                        db.mark(active.url,"FAILED",id)
                        return false
                    }
                }
            }
            if(options.any{it.state=="FAILED"})return false
            val ready=options.firstOrNull{it.state=="READY"}?:return false
            val link=Mp4SeriesLink(ready.url,ready.title,ready.volume,
                work.type=="EPUB",false,work.type=="ZIP")
            val id=Mp4SeriesSync.enqueue(context,work,link,ready.url)
                ?:return false
            if(id<0L){
                // External inventory explicitly confirmed this volume.
                db.mark(ready.url,"PRESENT")
                expected++
                continue
            }
            db.mark(ready.url,"QUEUED",id)
            schedule(context)
            return true
        }
        return false
    }

    private fun hasActive(context:Context):Boolean =
        Mp4SeriesStore(context).use{db->
            db.list().any{work->
                db.orderedCandidates(work.id,work.type).any{
                    it.state=="QUEUED" && it.downloadId!=null
                }
            }
        }

    private fun schedule(context:Context,force:Boolean=false){
        if(!AppPower.enabled(context))return
        val manager=context.getSystemService(JobScheduler::class.java)
        if(!force && manager.allPendingJobs.any{it.id==JOB_ID})return
        manager.schedule(JobInfo.Builder(JOB_ID,
            ComponentName(context,Mp4VolumeJob::class.java))
            .setPersisted(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(30_000L)
            .setOverrideDeadline(120_000L).build())
    }

    fun advance(context:Context){
        if(!AppPower.enabled(context))return
        Mp4SeriesStore(context).use{db->
            for(work in db.list())dispatch(context,db,work)
        }
    }
    fun afterJob(context:Context){
        if(hasActive(context))schedule(context,true)
    }
}

class Mp4VolumeJob:JobService(){
    override fun onStartJob(params:JobParameters):Boolean{
        thread(name="asterion-ordered-volume-check"){
            try{Mp4VolumeDispatcher.advance(applicationContext)}
            finally{
                jobFinished(params,false)
                Mp4VolumeDispatcher.afterJob(applicationContext)
            }
        }
        return true
    }
    override fun onStopJob(params:JobParameters)=true
}
