package com.azrael.pixivdumpsync

import android.app.DownloadManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.azrael.galleryflow.HttpClient
import com.azrael.galleryflow.SiteSession
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** MP4Directs series-level discovery and acquisition through verified originals.
 * Existing local downloads and cloud files are not assumed absent; only an
 * authoritative Rounder inventory item can establish remote presence.
 */
object Mp4SeriesSync {
    private const val PERIODIC=49896
    private const val IMMEDIATE=49897
    private val busy=AtomicBoolean(false)
    private val catalogLn="https://mp4directs.com/forums/ln.178/"
    private val catalogManga="https://mp4directs.com/threads/manga-download-links-index.2623/"
    private val alternateManga="https://mp4directs.com/forums/manga-download-list.199/"
    fun ensure(context:Context){
        if(!AppPower.enabled(context))return
        val jobs=context.getSystemService(JobScheduler::class.java)
        if(jobs.allPendingJobs.none{it.id==PERIODIC})
            jobs.schedule(JobInfo.Builder(PERIODIC,
                ComponentName(context,Mp4SeriesJob::class.java))
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(6L*60*60*1000).build())
    }
    fun scanNow(context:Context){
        if(!AppPower.enabled(context))return
        context.getSystemService(JobScheduler::class.java).schedule(
            JobInfo.Builder(IMMEDIATE,ComponentName(context,Mp4SeriesJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(1000L)
                .setOverrideDeadline(30_000L).build())
    }
    private fun page(url:String):Document{
        require(SourceIndexParser.safe(url) &&
            URI(url).host.orEmpty().removePrefix("www.")=="mp4directs.com")
        val response=Jsoup.connect(url).userAgent(HttpClient.USER_AGENT)
            .timeout(24000).maxBodySize(10_000_000)
            .followRedirects(false).ignoreHttpErrors(true)
            .apply{SiteSession.cookie(url)?.let{header("Cookie",it)}}
            .execute()
        if(response.statusCode() in setOf(401,403))
            error("MP4Directs account session required (HTTP ${response.statusCode()})")
        require(response.statusCode() in 200..299){
            "MP4Directs HTTP ${response.statusCode()}"
        }
        return response.parse()
    }
    private fun nameFor(url:String,work:Mp4Follow):String{
        val suffix=if(work.type=="ZIP")".zip" else ".epub"
        val digest=MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray()).take(6)
            .joinToString(""){"%02x".format(it.toInt() and 255)}
        return "mp4directs_"+digest+suffix
    }
    private fun enqueue(context:Context,work:Mp4Follow,
                        candidate:Mp4SeriesLink,referer:String):Boolean {
        if(candidate.isThread || candidate.volume==null ||
           !Mp4SeriesRules.eligible(candidate.volume,work.firstVolume) ||
           (work.type=="EPUB" && !candidate.isEpub) ||
           (work.type=="ZIP" && !candidate.isImageZip) ||
           !SourceIndexParser.safe(candidate.url))return false
        if(RounderInventoryIndex.isConfirmedPresent(context,work.name,
                candidate.volume,work.type))return true
        if(!CrossSourceDedup(context).use{it.reserve(candidate.url)})
            return CrossSourceDedup(context).use{it.alreadyHandled(candidate.url)}
        return try {
            val filename=nameFor(candidate.url,work)
            val folder=if(work.type=="EPUB")"Books" else "Comics"
            val request=DownloadManager.Request(Uri.parse(candidate.url)).apply{
                setTitle(work.name+" volume "+candidate.volume)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                    "AsterionDownloader/"+folder+"/MP4Directs/"+filename)
                addRequestHeader("User-Agent",HttpClient.USER_AGENT)
                SiteSession.cookie(candidate.url)?.let{addRequestHeader("Cookie",it)}
                ResourceDownloads.privacyReferer(candidate.url,referer)?.let{
                    addRequestHeader("Referer",it)
                }
            }
            val manager=context.getSystemService(DownloadManager::class.java)
            val id=manager.enqueue(request)
            CrossSourceDedup(context).use{it.attach(candidate.url,id)}
            DirectDownloadTracker.record(context,id,filename,folder,candidate.url)
            DirectDownloadRetry.remember(context,id,candidate.url,
                "$folder/MP4Directs",filename,referer)
            RounderDispatch.ensure(context)
            true
        }catch(e:Exception){
            CrossSourceDedup(context).use{it.release(candidate.url)}
            false
        }
    }
    private fun scanWork(context:Context,db:Mp4SeriesStore,work:Mp4Follow){
        if(!work.sync&&!work.backfill)return
        val seed=if(work.type=="ZIP")
            listOf(catalogManga,alternateManga)
        else listOf(catalogLn)
        val found=LinkedHashMap<String,Mp4SeriesLink>()
        val docs=ArrayList<Document>()
        for(address in seed){
            val doc=page(address)
            docs+=doc
            for(link in Mp4SeriesRules.forumLinks(doc,address,work.name,work.aliases))
                found.putIfAbsent(link.url,link)
        }
        // Only related works visibly listed under the parent series are added.
        // The parent and each spin-off have independent backfill floors.
        if(work.name==work.family && work.type=="EPUB"){
            for(doc in docs+listOf(page(catalogManga))){
                for(title in Mp4SeriesRules.relatedNames(doc,work.name,work.aliases)){
                    if(Mp4SeriesRules.normalize(title) !=
                            Mp4SeriesRules.normalize(work.name))
                        db.add(work.family,title,1,"ZIP")
                }
            }
        }
        val inspected=HashSet<String>()
        for(link in found.values.take(45)){
            db.observe(work,link)
            if(!link.isThread){
                if(work.backfill||work.sync)
                    if(enqueue(context,work,link,seed.first()))
                        db.mark(link.url,"QUEUED")
                continue
            }
            if(!inspected.add(link.url))continue
            val doc=page(link.url)
            for(original in Mp4SeriesRules.threadOriginals(doc,link.url,work.type)){
                db.observe(work,original)
                if(work.backfill||work.sync){
                    if(enqueue(context,work,original,link.url))
                        db.mark(original.url,"QUEUED")
                }
            }
        }
        db.checked(work.id,null,null)
    }
    fun scan(context:Context,stop:()->Boolean={false}){
        if(!AppPower.enabled(context)||!busy.compareAndSet(false,true))return
        try{
            Mp4SeriesStore(context).use{db->
                for(work in db.list()){
                    if(stop()||!AppPower.enabled(context))break
                    runCatching{scanWork(context,db,work)}.onFailure{
                        db.checked(work.id,work.cursor,
                            it.message?:it.javaClass.simpleName)
                    }
                }
            }
        }finally{busy.set(false)}
    }
}
class Mp4SeriesJob:JobService(){
    private val stopped=AtomicBoolean(false)
    override fun onStartJob(params:JobParameters):Boolean{
        stopped.set(false)
        thread(name="asterion-mp4-series"){
            try{Mp4SeriesSync.scan(applicationContext){stopped.get()}}
            finally{jobFinished(params,false)}
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean{
        stopped.set(true)
        return true
    }
}
