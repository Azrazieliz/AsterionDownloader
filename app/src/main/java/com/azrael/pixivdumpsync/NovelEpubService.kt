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
import com.azrael.galleryflow.HttpClient
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
            val response=Jsoup.connect(url).userAgent(HttpClient.USER_AGENT)
                .timeout(24000).maxBodySize(6_000_000)
                .followRedirects(false).ignoreHttpErrors(true)
                .apply{SiteSession.cookie(url)?.let{header("Cookie",it)}}
                .execute()
            if(response.statusCode() in 300..399){
                val destination=response.header("Location")
                    ?.takeIf{it.isNotBlank()}?:error("Chapter redirect has no target")
                url=URI(url).resolve(destination).toString()
            }else{
                if(response.statusCode() in setOf(401,403))
                    error("Novel source HTTP "+response.statusCode()+
                        ": connect your eligible website account in the browser")
                require(response.statusCode() in 200..299){
                    "Novel source unavailable: HTTP "+response.statusCode()
                }
                val result=response.parse()
                if(result.title().contains("Just a moment",true))
                    error("Novel source requires interactive browser verification")
                return result
            }
        }
        error("Novel source redirect limit exceeded")
    }
    private data class Discovery(val title:String,val chapters:List<NovelChapter>,
                                 val observed:String,val cacheReused:Boolean)
    private fun fingerprint(chapters:List<NovelChapter>):String {
        val bytes=chapters.joinToString("\n"){it.url+"\t"+it.number+"\t"+it.title}
            .toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    private fun existingCatalog(context:Context,url:String,visible:String):List<NovelChapter>? =
        runCatching {
            val file=File(cacheDir(context,url),"catalog-index.json")
            if(!file.isFile || System.currentTimeMillis()-file.lastModified()>
                7L*24L*60L*60_000L)return null
            val root=JSONObject(file.readText())
            if(root.optString("visible")!=visible)return null
            val entries=root.getJSONArray("chapters")
            val directory=cacheDir(context,url)
            val chapters=(0 until entries.length()).map{i->
                val item=entries.getJSONObject(i)
                NovelChapter(item.getDouble("number"),item.getString("title"),
                    item.getString("url"))
            }
            if(chapters.isEmpty() || chapters.any{
                !File(directory,chapterKey(it.url)+".json").isFile
            })return null
            chapters
        }.getOrNull()
    private fun saveCatalog(context:Context,url:String,scan:Discovery){
        val directory=cacheDir(context,url)
        val items=JSONArray()
        scan.chapters.forEach{chapter->
            items.put(JSONObject().apply {
                put("url",chapter.url);put("title",chapter.title)
                put("number",chapter.number)
            })
        }
        val file=File(directory,"catalog-index.json")
        val temp=File(directory,"catalog-index.part")
        temp.writeText(JSONObject().apply{
            put("visible",scan.observed);put("chapters",items)
        }.toString())
        if(!temp.renameTo(file)){
            file.delete()
            check(temp.renameTo(file)){"Unable to record novel chapter catalogue"}
        }
    }
    private fun discover(context:Context,url:String,stop:()->Boolean):Discovery {
        val seen=HashSet<String>()
        val found=LinkedHashMap<String,NovelChapter>()
        var current:String?=url
        var title="Novel"
        var visible=""
        while(current!=null && seen.size<500 && !stop()){
            if(!seen.add(current))break
            val doc=page(context,current,url)
            if(seen.size==1)title=doc.selectFirst("h1")?.text()?.take(180)
                ?.takeIf{it.isNotBlank()}?:doc.title().take(180)
            val onPage=NovelEpubParser.chapters(doc,url)
            if(seen.size==1){
                visible=fingerprint(onPage)
                // FreeWebNovel's first catalogue page may remain unchanged
                // while new chapters are added at the end. Probe the actual
                // last reader link, not only the first-page fingerprint.
                if(URI(url).host.orEmpty().removePrefix("www.")==
                        "freewebnovel.com"){
                    existingCatalog(context,url,visible)?.let{cached->
                        val last=cached.maxWithOrNull(
                            compareBy<NovelChapter>{it.number}.thenBy{it.url})
                            ?:error("Cached chapter catalogue is empty")
                        val tail=page(context,last.url,url)
                        var next=NovelEpubParser.nextReader(tail,url,last.url)
                        if(next==null)return Discovery(title,cached,visible,true)
                        val expanded=LinkedHashMap<String,NovelChapter>()
                        cached.forEach{expanded[it.url]=it}
                        val walked=HashSet<String>(expanded.keys)
                        val dir=cacheDir(context,url)
                        while(next!=null && walked.size<10000 && !stop()){
                            val address=next
                            if(!walked.add(address))break
                            val reader=page(context,address,url)
                            val chapter=NovelEpubParser.readerChapter(reader,address)
                            expanded[address]=chapter
                            storeChapterText(chapter,NovelEpubParser.chapterText(reader),dir)
                            next=NovelEpubParser.nextReader(reader,url,address)
                        }
                        require(next==null && !stop()){
                            "Reader backfill stopped before reaching the last accessible link"
                        }
                        return Discovery(title,expanded.values.sortedWith(
                            compareBy<NovelChapter>{it.number}.thenBy{it.url}),
                            visible,false)
                    }
                }
            }
            for(item in onPage)found.putIfAbsent(item.url,item)
            // Index links identify observed chapters, not a definitive total.
            current=NovelEpubParser.nextIndex(doc,url,seen)
        }
        // Follow the provider's observed reader links without inferring a
        // published chapter total from numerically encoded URLs or labels.
        if(URI(url).host.orEmpty().removePrefix("www.")=="freewebnovel.com" &&
            !stop()){
            val first=found.values.minByOrNull{it.number}
                ?:error("Cannot find starting chapter for complete archive")
            val walked=HashSet<String>()
            val dir=cacheDir(context,url)
            var link:String?=first.url
            while(link!=null && walked.size<10000 && !stop()){
                val address=link
                if(!walked.add(address))break
                val doc=page(context,address,url)
                val actual=NovelEpubParser.readerChapter(doc,address)
                found[address]=actual
                storeChapterText(actual,NovelEpubParser.chapterText(doc),dir)
                link=NovelEpubParser.nextReader(doc,url,address)
            }
            require(link==null){"Reader chapter pagination did not finish"}
        }
        check(!stop()){"EPUB job stopped before chapter discovery ended"}
        check(current==null){"Novel index pagination did not finish"}
        val chapters=found.values.sortedWith(compareBy<NovelChapter>{it.number}
            .thenBy{it.url})
        require(chapters.isNotEmpty()){"No accessible numbered chapters discovered"}
        return Discovery(title,chapters,visible,false)
    }
    private fun cacheDir(context:Context,url:String):File{
        val key=MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray()).take(16)
            .joinToString(""){"%02x".format(it.toInt() and 255)}
        return File(context.filesDir,"novel-epub-cache/"+key).apply{mkdirs()}
    }
    /** Real count of completed chapter-cache records, never a guessed percentage. */
    fun cachedCount(context:Context,url:String):Int=
        cacheDir(context,url).listFiles()?.count{
            it.isFile && it.name.endsWith(".json")
        }?:0

    private fun chapterKey(url:String):String =
        MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray()).take(10)
            .joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun storeChapterText(chapter:NovelChapter,text:NovelText,directory:File){
        val id=chapterKey(chapter.url)
        val cached=File(directory,id+".json")
        if(cached.isFile)return
        val body=JSONObject().apply{
            put("title",chapter.title)
            put("paragraphs",JSONArray(text.paragraphs))
        }
        val tmp=File(directory,id+".part")
        tmp.writeText(body.toString())
        check(tmp.renameTo(cached)){"Unable to cache extracted chapter"}
    }
    private fun chapterText(context:Context,source:String,chapter:NovelChapter,
                            directory:File):NovelText {
        val id=chapterKey(chapter.url)
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
        storeChapterText(chapter,text,directory)
        return NovelText(chapter.title,text.paragraphs)
    }
    private fun safeName(raw:String):String=raw
        .replace(Regex("[^\\p{L}\\p{N} ._-]"),"_").trim().take(85)
        .ifBlank{"Novel"}
    private fun commit(context:Context,file:File,title:String,asZip:Boolean):String {
        val extension=if(asZip)".zip" else ".epub"
        val values=ContentValues().apply{
            put(MediaStore.Downloads.DISPLAY_NAME,safeName(title)+extension)
            put(MediaStore.Downloads.MIME_TYPE,
                if(asZip)"application/zip" else "application/epub+zip")
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
    private fun generate(context:Context,task:NovelEpubTask,stop:()->Boolean,
                         db:NovelEpubQueue):Pair<String,String>{
        val scan=discover(context,task.url,stop)
        val (title,chapters)=scan
        val signature=fingerprint(chapters)
        val cached=db.previous(task.url)
        if(cached!=null && cached.second==signature){
            // Only treat this as an existing successful EPUB when Android can
            // still open the actual MediaStore entry. Never guess cloud presence.
            val present=runCatching{
                context.contentResolver.openAssetFileDescriptor(
                    Uri.parse(cached.first),"r")?.use{it.length!=0L}?:false
            }.getOrDefault(false)
            if(present)return cached
        }
        val cache=cacheDir(context,task.url)
        val part=File(cache,"novel.epub.part")
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
            require(part.length()>2000){"Book archive content unexpectedly empty"}
            val saved=commit(context,part,title,false)
            if(!scan.cacheReused)saveCatalog(context,task.url,scan)
            if(NduSettings.configured(context)){
                NduCloudQueue(context).use{cloud->
                    cloud.enqueue("books",safeName(title)+".epub",Uri.parse(saved))
                }
                NduUploadService.launch(context)
            }
            return saved to signature
        }finally{part.delete()}
    }
    fun run(context:Context,stop:()->Boolean={false}){
        if(!AppPower.enabled(context)||!busy.compareAndSet(false,true))return
        BackfillPerformance.backgroundWorker()
        try{
            NovelEpubQueue(context).use{db->
                db.recover()
                db.refreshDue()
                for(i in 0 until 2){
                    if(stop()||!AppPower.enabled(context))break
                    val task=db.next()?:break
                    db.started(task.url)
                    try{
                        val (saved,signature)=generate(context,task,stop,db)
                        db.completed(task.url,saved,signature)
                    }catch(t:Exception){db.failure(task,t.message?:t.javaClass.simpleName)}
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
