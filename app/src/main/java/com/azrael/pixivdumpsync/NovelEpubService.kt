package com.azrael.pixivdumpsync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.azrael.galleryflow.SiteSession
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Browser-session-compatible chapter to EPUB3 acquisition.
 * Never exports a partial book: each discovered chapter must be accessible
 * and chapter-list pagination must be exhausted before the final EPUB commits.
 */
object NovelEpubService {
    private const val JOB=49874
    private const val PERIODIC=49875
    private val busy=AtomicBoolean(false)
    fun enqueue(context:Context,url:String,title:String):Boolean {
        val fresh=NovelEpubQueue(context).use{it.enqueue(url,title)}
        ensure(context)
        schedule(context)
        return fresh
    }
    fun ensure(context:Context){
        if(!AppPower.enabled(context))return
        val jobs=context.getSystemService(JobScheduler::class.java)
        if(jobs.allPendingJobs.none{it.id==PERIODIC}){
            jobs.schedule(JobInfo.Builder(PERIODIC,
                ComponentName(context,NovelEpubJob::class.java))
                .setPersisted(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15*60_000L).build())
        }
    }
    fun schedule(context:Context){
        if(!AppPower.enabled(context))return
        context.getSystemService(JobScheduler::class.java).schedule(
            JobInfo.Builder(JOB,ComponentName(context,NovelEpubJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(1000L).setOverrideDeadline(60_000L).build())
    }
    private fun page(context:Context,address:String,source:String):Document {
        var url=address
        repeat(4){
            require(SourceIndexParser.safe(url) &&
                SourceIndexParser.sameSite(source,url)){
                "A novel source tried to fetch an untrusted address"
            }
            val response=Jsoup.connect(url).userAgent(
                "Mozilla/5.0 (Linux; Android 17; Mobile) AsterionDownloader/1.3")
                .timeout(24000).maxBodySize(6_000_000)
                .followRedirects(false).ignoreHttpErrors(true)
                .apply{SiteSession.cookie(url)?.let{header("Cookie",it)}}
                .execute()
            if(response.statusCode() in 300..399){
                val destination=response.header("Location")
                    ?.takeIf{it.isNotBlank()}?:error("Chapter redirect has no target")
                url=URI(url).resolve(destination).toString()
            }else{
                require(response.statusCode() in 200..299){
                    "Novel source unavailable: HTTP "+response.statusCode()
                }
                val result=response.parse()
                if(result.title().contains("Just a moment",true))
                    error("Novel source requires an authenticated site session")
                return result
            }
        }
        error("Novel source redirect limit exceeded")
    }
    private fun discover(context:Context,url:String,stop:()->Boolean):
            Pair<String,List<NovelChapter>> {
        val seen=HashSet<String>()
        val found=LinkedHashMap<String,NovelChapter>()
        var current:String?=url
        var title="Novel"
        var expected=0
        while(current!=null && seen.size<500 && !stop()){
            if(!seen.add(current))break
            val doc=page(context,current,url)
            if(seen.size==1)title=doc.selectFirst("h1")?.text()?.take(180)
                ?.takeIf{it.isNotBlank()}?:doc.title().take(180)
            for(item in NovelEpubParser.chapters(doc,url))
                found.putIfAbsent(item.url,item)
            expected=maxOf(expected,NovelEpubParser.chapterCountHint(doc))
            current=NovelEpubParser.nextIndex(doc,url,seen)
        }
        check(!stop()){"EPUB job stopped before chapter discovery ended"}
        check(current==null){"Novel index pagination did not finish"}
        val chapters=found.values.sortedWith(compareBy<NovelChapter>{it.number}
            .thenBy{it.url})
        require(chapters.isNotEmpty()){"No accessible numbered chapters discovered"}
        require(expected<=chapters.size){
            "Novel index listed at least "+expected+" chapters, but only "+
                chapters.size+" were found; refusing incomplete EPUB"
        }
        // The indexed chapter numbers must not have unexplained whole-number
        // gaps; decimal interludes (e.g., 10.5) remain legal.
        val whole=chapters.map{it.number}
            .filter{it==kotlin.math.floor(it)}.map{it.toInt()}.distinct().sorted()
        if(whole.size>1) {
            val missing=(whole.first()..whole.last()).count{it !in whole}
            require(missing==0){
                "Missing "+missing+" numbered chapters; refusing incomplete EPUB"
            }
        }
        return title to chapters
    }
    private fun cacheDir(context:Context,url:String):File{
        val key=MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray()).take(16)
            .joinToString(""){"%02x".format(it.toInt() and 255)}
        return File(context.filesDir,"novel-epub-cache/"+key).apply{mkdirs()}
    }
    private fun chapterText(context:Context,source:String,chapter:NovelChapter,
                            directory:File):NovelText {
        val id=MessageDigest.getInstance("SHA-256")
            .digest(chapter.url.toByteArray()).take(10)
            .joinToString(""){"%02x".format(it.toInt() and 255)}
        val cached=File(directory,id+".json")
        if(cached.isFile){
            runCatching{
                val data=JSONObject(cached.readText())
                val list=data.getJSONArray("paragraphs")
                val paragraphs=(0 until list.length()).map{list.getString(it)}
                require(paragraphs.sumOf{it.length}>80)
                return NovelText(data.getString("title"),paragraphs)
            }
        }
        val text=NovelEpubParser.chapterText(page(context,chapter.url,source))
        val body=JSONObject().apply{
            put("title",chapter.title)
            put("paragraphs",JSONArray(text.paragraphs))
        }
        val tmp=File(directory,id+".part")
        tmp.writeText(body.toString())
        check(tmp.renameTo(cached)){"Unable to cache extracted chapter"}
        return NovelText(chapter.title,text.paragraphs)
    }
    private fun safeName(raw:String):String=raw
        .replace(Regex("[^\\p{L}\\p{N} ._-]"),"_").trim().take(85)
        .ifBlank{"Novel"}
    private fun commit(context:Context,file:File,title:String):String {
        val values=ContentValues().apply{
            put(MediaStore.Downloads.DISPLAY_NAME,safeName(title)+".epub")
            put(MediaStore.Downloads.MIME_TYPE,"application/epub+zip")
            put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS+"/AsterionDownloader/Books/")
            put(MediaStore.Downloads.IS_PENDING,1)
        }
        val resolver=context.contentResolver
        val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)
            ?:error("Cannot create EPUB download")
        try{
            resolver.openOutputStream(uri,"w")!!.use{output->
                file.inputStream().buffered().use{it.copyTo(output,256*1024)}
            }
            val written=resolver.openAssetFileDescriptor(uri,"r")
                ?.use{it.length}?:-1L
            require(written<0 || written==file.length()){"Incomplete EPUB file copy"}
            resolver.update(uri,ContentValues().apply{
                put(MediaStore.Downloads.IS_PENDING,0)
            },null,null)
            return uri.toString()
        }catch(t:Exception){
            resolver.delete(uri,null,null)
            throw t
        }
    }
    private fun generate(context:Context,task:NovelEpubTask,stop:()->Boolean):String{
        val (title,chapters)=discover(context,task.url,stop)
        val cache=cacheDir(context,task.url)
        val part=File(cache,"complete.epub.part")
        try{
            FileOutputStream(part).use{output->
                // Build only after ALL chapter texts are acquired, so a failed
                // response cannot create a plausible-looking partial book.
                val contents=ArrayList<NovelText>(chapters.size)
                for(chapter in chapters){
                    check(!stop()){"EPUB chapter retrieval was interrupted"}
                    contents+=chapterText(context,task.url,chapter,cache)
                }
                NovelEpubParser.writeEpub(output,title,task.url,contents)
            }
            require(part.length()>2000){"EPUB content unexpectedly empty"}
            val saved=commit(context,part,title)
            if(NduSettings.configured(context)){
                NduCloudQueue(context).use{cloud->
                    cloud.enqueue("books",safeName(title)+".epub",Uri.parse(saved))
                }
                NduUploadService.launch(context)
            }
            return saved
        }finally{part.delete()}
    }
    fun run(context:Context,stop:()->Boolean={false}){
        if(!AppPower.enabled(context)||!busy.compareAndSet(false,true))return
        try{
            NovelEpubQueue(context).use{db->
                db.recover()
                for(i in 0 until 2){
                    if(stop()||!AppPower.enabled(context))break
                    val task=db.next()?:break
                    db.started(task.url)
                    try{db.completed(task.url,generate(context,task,stop))}
                    catch(t:Exception){db.failure(task,t.message?:t.javaClass.simpleName)}
                }
            }
        }finally{busy.set(false)}
    }
}
class NovelEpubJob:JobService(){
    private val stopped=AtomicBoolean(false)
    override fun onStartJob(params:JobParameters):Boolean{
        stopped.set(false)
        thread(name="asterion-novel-epub"){
            try{NovelEpubService.run(applicationContext){stopped.get()}}
            finally{
                jobFinished(params,false)
                if(!stopped.get() && AppPower.enabled(applicationContext)){
                    val more=NovelEpubQueue(applicationContext).use{it.hasDue()}
                    if(more)NovelEpubService.schedule(applicationContext)
                }
            }
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean{
        stopped.set(true)
        return true
    }
}
