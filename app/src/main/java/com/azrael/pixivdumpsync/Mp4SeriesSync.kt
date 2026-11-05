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
        // Start with latest listings and the retained historical cursor.
        // Related-work titles must come from a real category/index link.
        val queue=ArrayDeque<Pair<String,Int>>()
        seed.forEach{queue.addLast(it to 0)}
        work.cursor?.takeIf{SourceIndexParser.safe(it)}?.let{
            queue.addLast(it to 1)
        }
        if(work.name==work.family && work.type=="EPUB")
            queue.addLast(catalogManga to 0)
        val visited=HashSet<String>()
        var scanned=0
        val budget=if(work.backfill)32 else 4
        while(queue.isNotEmpty() && scanned<budget){
            val (address,depth)=queue.removeFirst()
            if(!visited.add(address))continue
            val doc=page(address)
            scanned++
            if(address==catalogManga && work.name==work.family &&
                    work.type=="EPUB"){
                for(title in Mp4SeriesRules.relatedNames(doc,work.name,work.aliases)){
                    if(Mp4SeriesRules.normalize(title)!=
                            Mp4SeriesRules.normalize(work.name))
                        db.add(work.family,title,1,"ZIP")
                }
            }
            val links=Mp4SeriesRules.forumLinks(doc,address,work.name,work.aliases)
            for(link in links.take(150)){
                db.observe(work,link)
                if(link.isThread && depth<3){
                    if(link.url !in visited)queue.addLast(link.url to depth+1)
                }else if(!link.isThread &&
                        enqueue(context,work,link,address)){
                    db.mark(link.url,"QUEUED")
                }
            }
            if(address.contains("/threads/")){
                for(original in Mp4SeriesRules.threadOriginals(
                        doc,address,work.type).take(100)){
                    db.observe(work,original)
                    if(enqueue(context,work,original,address))
                        db.mark(original.url,"QUEUED")
                }
            }
            if(work.backfill && depth<4){
                val next=SourceIndexParser.next(doc,address,address)
                if(next!=null && next !in visited &&
                    SourceIndexParser.sameSite(address,next))
                    queue.addLast(next to depth+1)
            }
        }
        // A non-null cursor means historical discovery still has work to do.
        // Null only means no more pagination was observed in this pass,
        // NOT that all published volumes or spin-offs necessarily exist here.
        db.checked(work.id,queue.firstOrNull()?.first,null)
    }
    fun scan(context:Context,stop:()->Boolean={false}){
        if(!AppPower.enabled(context)||!busy.compareAndSet(false,true))return
        try{
            Mp4SeriesStore(context).use{db->
                val visited=HashSet<Long>()
                // A first pass can discover related work subscriptions. The
                // second pass begins their independent historical scan now.
                repeat(2){
                    for(work in db.list()){
                        if(stop()||!AppPower.enabled(context))break
                        if(!visited.add(work.id))continue
                        runCatching{scanWork(context,db,work)}.onFailure{
                            db.checked(work.id,work.cursor,
                                it.message?:it.javaClass.simpleName)
                        }
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
