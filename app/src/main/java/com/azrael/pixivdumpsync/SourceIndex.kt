package com.azrael.pixivdumpsync

import android.app.DownloadManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Environment
import com.azrael.galleryflow.SiteSession
import com.asterion.videoflow.VideoDb
import com.asterion.videoflow.VideoEngine
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class IndexedSource(val url:String,val name:String,val area:String,val enabled:Boolean,
    val cursor:String?,val lastCheck:Long,val error:String?,val discovered:Int,val adapter:String)
data class IndexedItem(val url:String,val title:String,val preview:String?,val source:String,
    val area:String,val kind:String,val discoveredAt:Long,val queued:Boolean)

class SourceIndexDb(context:Context):
    SQLiteOpenHelper(context,"asterion_source_index.sqlite",null,1) {
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("""CREATE TABLE sources(
            url TEXT PRIMARY KEY,name TEXT NOT NULL,area TEXT NOT NULL,
            enabled INTEGER NOT NULL DEFAULT 1,cursor TEXT,last_check INTEGER NOT NULL DEFAULT 0,
            error TEXT,discovered INTEGER NOT NULL DEFAULT 0,adapter TEXT NOT NULL DEFAULT 'generic')""")
        db.execSQL("""CREATE TABLE entries(
            url TEXT PRIMARY KEY,source TEXT NOT NULL,title TEXT NOT NULL,preview TEXT,
            area TEXT NOT NULL,kind TEXT NOT NULL,discovered_at INTEGER NOT NULL,
            queued INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX entries_source ON entries(source,discovered_at)")
        db.execSQL("CREATE INDEX entries_area ON entries(area,discovered_at)")
    }
    override fun onUpgrade(db:SQLiteDatabase,a:Int,b:Int){}
    fun add(url:String,name:String,area:String,adapter:String="generic"){
        val v=ContentValues().apply{
            put("url",url);put("name",name);put("area",area);put("adapter",adapter)
        }
        writableDatabase.insertWithOnConflict("sources",null,v,SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun enable(url:String,value:Boolean){
        val v=ContentValues().apply{put("enabled",if(value)1 else 0)}
        writableDatabase.update("sources",v,"url=?",arrayOf(url))
    }
    fun reset(url:String){
        writableDatabase.execSQL("UPDATE sources SET cursor=NULL,error=NULL WHERE url=?",arrayOf(url))
    }
    fun update(url:String,cursor:String?,error:String?,count:Int){
        val v=ContentValues().apply{
            if(cursor==null)putNull("cursor") else put("cursor",cursor)
            if(error==null)putNull("error") else put("error",error.take(280))
            put("last_check",System.currentTimeMillis())
            put("discovered",count)
        }
        writableDatabase.update("sources",v,"url=?",arrayOf(url))
    }
    fun sources():List<IndexedSource>{
        val out=ArrayList<IndexedSource>()
        readableDatabase.rawQuery("""SELECT url,name,area,enabled,cursor,last_check,error,
                discovered,adapter FROM sources ORDER BY area,name COLLATE NOCASE""",null).use{c->
            while(c.moveToNext())out+=IndexedSource(c.getString(0),c.getString(1),
                c.getString(2),c.getInt(3)==1,c.getString(4),c.getLong(5),
                c.getString(6),c.getInt(7),c.getString(8))
        }
        return out
    }
    fun upsert(item:IndexedItem):Boolean {
        val v=ContentValues().apply{
            put("url",item.url);put("title",item.title.take(220))
            put("source",item.source);put("area",item.area);put("kind",item.kind)
            if(item.preview!=null)put("preview",item.preview)
            put("discovered_at",item.discoveredAt)
        }
        return writableDatabase.insertWithOnConflict("entries",null,v,
            SQLiteDatabase.CONFLICT_IGNORE)!=-1L
    }
    fun markQueued(url:String){
        val v=ContentValues().apply{put("queued",1)}
        writableDatabase.update("entries",v,"url=?",arrayOf(url))
    }
    fun items(area:String?=null,limit:Int=60):List<IndexedItem>{
        val out=ArrayList<IndexedItem>()
        val sql="""SELECT url,title,preview,source,area,kind,discovered_at,queued
            FROM entries """+(if(area==null)"" else "WHERE area=? ")+
            "ORDER BY discovered_at DESC LIMIT ?"
        val args=if(area==null)arrayOf(limit.toString()) else arrayOf(area,limit.toString())
        readableDatabase.rawQuery(sql,args).use{c->
            while(c.moveToNext())out+=IndexedItem(c.getString(0),c.getString(1),
                c.getString(2),c.getString(3),c.getString(4),c.getString(5),
                c.getLong(6),c.getInt(7)==1)
        }
        return out
    }
}

internal object SourceIndexParser {
    private val junk=Regex("(?i)(?:/login|/signup|/register|/privacy|/terms|/contact|/tag/|/tags/|/category/|/account|/settings|/advert|/report)")
    private val media=Regex("(?i)\\.(?:mp4|m4v|webm|mkv|mov|epub|zip|7z|rar|blend|fbx|obj|glb|gltf|stl|apk|torrent|png|jpg|jpeg|webp)(?:[?#]|$)")
    internal fun safe(url:String):Boolean=runCatching{
        val uri=URI(url)
        uri.scheme=="https" && SearchRanking.validUrl(url) &&
            !uri.host.isNullOrBlank() && !uri.host.contains(":") &&
            !uri.host.endsWith(".internal",true) && uri.userInfo==null
    }.getOrDefault(false)
    private fun sameSite(a:String,b:String):Boolean{
        val x=runCatching{URI(a).host.lowercase().removePrefix("www.")}.getOrDefault("")
        val y=runCatching{URI(b).host.lowercase().removePrefix("www.")}.getOrDefault("")
        return x.isNotEmpty() && (x==y || x.endsWith("."+y) || y.endsWith("."+x))
    }
    internal fun kind(url:String,area:String):String {
        val path=runCatching{URI(url).path.lowercase()}.getOrDefault("")
        return when{
            path.endsWith(".epub")->"epub"
            path.endsWith(".torrent")->"torrent"
            path.endsWith(".apk")->"apk"
            listOf(".blend",".fbx",".obj",".glb",".gltf",".stl").any{path.endsWith(it)}->"model"
            listOf(".mp4",".m4v",".webm",".mkv",".mov").any{path.endsWith(it)}->"video"
            listOf(".png",".jpg",".jpeg",".webp").any{path.endsWith(it)}->"image"
            listOf(".zip",".7z",".rar").any{path.endsWith(it)}->"archive"
            else->area.lowercase(Locale.ROOT)
        }
    }
    internal fun entries(doc:Document,base:String,area:String,clock:Long):List<IndexedItem>{
        val out=LinkedHashMap<String,IndexedItem>()
        val scoped=doc.select("article a[href],main a[href],.post a[href],.posts a[href],"+
            ".gallery a[href],.entry a[href],.item a[href],.content a[href],"+
            ".video a[href],.thumb a[href],a[href*='/post/'],a[href*='/view/']")
        val candidates=if(scoped.isEmpty())doc.select("a[href]") else scoped
        for(link in candidates.take(700)){
            val url=link.absUrl("href").substringBefore('#')
            if(!safe(url)||!sameSite(base,url)||junk.containsMatchIn(url))continue
            val path=URI(url).path.orEmpty()
            val direct=media.containsMatchIn(url)
            val detail=path.count{it=='/'}>=2 || path.endsWith(".html") ||
                path.contains("/view")||path.contains("/post")||path.contains("/watch") ||
                path.contains("/video")||path.contains("/product")||path.contains("/gallery")
            if(!direct && !detail)continue
            val type=kind(url,area)
            if(area=="BOOKS" && type!="epub" && !(path.contains("novel")||path.contains("book")))continue
            if(area=="GAMES" && type !in listOf("torrent","apk","archive") &&
                !(path.contains("game")||path.contains("view")))continue
            val title=link.attr("title").ifBlank{link.text()}.ifBlank{
                link.selectFirst("img")?.attr("alt").orEmpty()
            }.trim().take(180)
            if(title.length<3 || title.equals("next",true) || title.equals("previous",true))continue
            val image=link.selectFirst("img")?.let { img->
                sequenceOf("data-original","data-src","data-lazy-src","src")
                    .map{img.absUrl(it)}.firstOrNull{safe(it)}
            } ?: link.parent()?.selectFirst("img")?.absUrl("src")?.takeIf{safe(it)}
            out.putIfAbsent(url,IndexedItem(url,title,image,base,area,type,clock,false))
            if(out.size>=110)break
        }
        return out.values.toList()
    }
    internal fun next(doc:Document,base:String):String? {
        val value=doc.selectFirst("a[rel=next][href],link[rel=next][href],"+
            "a.next[href],.pagination a.next[href]")?.absUrl("href")
        return value?.takeIf{safe(it)&&sameSite(it,base)&&it!=base}
    }
}

object SourceIndex {
    private const val RECURRING_ID=48531
    private const val CATCHUP_ID=48532
    private const val STATE="asterion_source_index_cursor"
    private val busy=AtomicBoolean(false)
    private val extra=listOf(
        Triple("Patreon","https://www.patreon.com/","CREATORS"),
        Triple("FANBOX","https://www.fanbox.cc/","CREATORS"),
        Triple("Fantia","https://fantia.jp/","CREATORS"),
        Triple("Gumroad","https://gumroad.com/","ASSETS"),
        Triple("OnlyFans","https://onlyfans.com/","CREATORS"),
        Triple("Fansly","https://fansly.com/","CREATORS"),
        Triple("KoikatsuCards","https://koikatsucards.com/","ASSETS"),
        Triple("Pawchive","https://pawchive.pw/","CREATORS"),
        Triple("Kemono","https://kemono.cr/","CREATORS"),
        Triple("Coomer","https://coomer.st/","CREATORS"),
        Triple("F95Zone","https://f95zone.to/","GAMES"),
        Triple("Shionlib","https://shionlib.com/","BOOKS"),
        Triple("Anna's Archive","https://annas-archive.org/","BOOKS")
    )
    fun ensure(context:Context){
        SourceIndexDb(context).use{db->
            for(site in AlohaSources.all)db.add(site.url,site.name,site.area.name,
                if(site.host in AlohaSources.alreadyIntegratedHosts)"native" else "generic")
            for((name,url,area) in extra)db.add(url,name,area,
                if(name in setOf("KoikatsuCards","Pawchive","Kemono","Coomer"))"native" else "generic")
            val added=context.getSharedPreferences("asterion_custom_site_enrollment",0)
                .getStringSet("sites",emptySet()).orEmpty()
            for(record in added){
                val delim=record.indexOf('|')
                if(delim<1)continue
                val type=record.substring(0,delim)
                val url=record.substring(delim+1)
                if(!SourceIndexParser.safe(url))continue
                val area=when(type){
                    "PIXIV","X","COSPLAY"->"COSPLAY"
                    "VIDEO"->"ADULT"
                    "BOOKS"->"BOOKS"
                    "COMICS"->"COMICS"
                    "TORRENTS"->"GAMES"
                    "ASSETS"->"ASSETS"
                    else->"CREATORS"
                }
                db.add(url,URI(url).host.orEmpty(),area,
                    if(type in setOf("PIXIV","X","COSPLAY","VIDEO"))"native" else "generic")
            }
        }
        val jobs=context.getSystemService(JobScheduler::class.java)
        if(jobs.allPendingJobs.none{it.id==RECURRING_ID})
            jobs.schedule(JobInfo.Builder(RECURRING_ID,
                ComponentName(context,SourceIndexJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true).setPeriodic(6L*60L*60L*1000L).build())
        if(context.getSharedPreferences(STATE,0).getBoolean("initial",false).not())
            scheduleCatchup(context)
    }
    private fun scheduleCatchup(context:Context){
        val jobs=context.getSystemService(JobScheduler::class.java)
        if(jobs.allPendingJobs.any{it.id==CATCHUP_ID})return
        jobs.schedule(JobInfo.Builder(CATCHUP_ID,ComponentName(context,SourceIndexJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(30_000L)
            .setOverrideDeadline(4L*60L*1000L).build())
    }
    fun scanNow(context:Context,area:String?=null){
        if(!AppPower.enabled(context))return
        thread(name="asterion-source-index"){scan(context,area)}
    }
    fun enroll(context:Context,address:String,area:String,sourceName:String,
               native:Boolean=false){
        if(!SourceIndexParser.safe(address))return
        SourceIndexDb(context).use{it.add(address,sourceName,area,
            if(native)"native" else "generic")}
        if(native||!AppPower.enabled(context))return
        thread(name="asterion-new-source-index"){
            val source=SourceIndexDb(context).use{db->
                db.sources().firstOrNull{it.url==address}
            }?:return@thread
            runCatching{scanOne(context,source)}.onFailure{
                SourceIndexDb(context).use{db->
                    db.update(address,source.cursor,it.message,source.discovered)
                }
            }
        }
    }
    fun scan(context:Context,area:String?=null,stop:()->Boolean={false}) {
        if(!AppPower.enabled(context)||!busy.compareAndSet(false,true))return
        try {
            val pref=context.getSharedPreferences(STATE,0)
            val all=SourceIndexDb(context).use{it.sources().filter{source->
                source.enabled && source.adapter=="generic" &&
                    (area==null||area==source.area)
            }}
            if(all.isEmpty())return
            val offset=if(area==null)pref.getInt("offset",0)%all.size else 0
            val batch=if(area==null)all.drop(offset).take(5) else all.take(20)
            for(source in batch){
                if(stop()||!AppPower.enabled(context))break
                runCatching{scanOne(context,source)}.onFailure{
                    SourceIndexDb(context).use{db->
                        db.update(source.url,source.cursor,it.message?:"Source unavailable",source.discovered)
                    }
                }
            }
            if(area==null && !stop()){
                val advanced=(offset+batch.size)%all.size
                pref.edit().putInt("offset",advanced)
                    .putBoolean("initial",advanced==0).apply()
                if(advanced!=0)scheduleCatchup(context)
            }
        }finally{busy.set(false)}
    }
    private fun scanOne(context:Context,source:IndexedSource){
        var cursor=source.cursor
        var fresh=0
        val pages=listOfNotNull(source.url,cursor?.takeIf{it!=source.url}).distinct()
        for(address in pages.take(2)){
            val doc=Jsoup.connect(address).timeout(16000).maxBodySize(3_000_000)
                .userAgent("Mozilla/5.0 (Linux; Android 17) AsterionDownloader/1.3")
                .header("Accept","text/html,application/xhtml+xml")
                .apply{SiteSession.cookie(address)?.let{header("Cookie",it)}}
                .get()
            val landing=doc.location()
            require(SourceIndexParser.safe(landing) &&
                URI(landing).host.equals(URI(source.url).host,true)){
                "Source redirected to another host; sign in through its account panel"
            }
            val records=SourceIndexParser.entries(doc,source.url,source.area,System.currentTimeMillis())
            SourceIndexDb(context).use{db->
                for(item in records){
                    if(db.upsert(item)){
                        fresh++
                        if(item.kind in setOf("video","epub","model") &&
                            (item.area!="BOOKS" || item.kind=="epub") &&
                            queueDirect(context,item)) db.markQueued(item.url)
                    }
                }
            }
            cursor=SourceIndexParser.next(doc,source.url)
            if(records.isEmpty() && fresh==0)
                throw IllegalStateException("No supported originals found; dedicated adapter or login required")
        }
        SourceIndexDb(context).use{db->
            db.update(source.url,cursor,null,source.discovered+fresh)
        }
    }
    private fun queueDirect(context:Context,item:IndexedItem):Boolean=runCatching{
        if(!SourceIndexParser.safe(item.url))return false
        if(item.kind=="video"){
            val queued=VideoDb(context).use{it.addVideo(item.url,item.title)}
            if(queued)VideoEngine.launch(context,false)
            return queued
        }
        val suffix=if(item.kind=="epub")".epub" else ".bin"
        val filename=URI(item.url).path.substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"),"_").take(100)
            .ifBlank{"asset_"+System.currentTimeMillis()+suffix}
        val folder=if(item.kind=="epub")"Books" else "Assets"
        val manager=context.getSystemService(DownloadManager::class.java)
        val req=DownloadManager.Request(Uri.parse(item.url)).apply{
            setTitle(filename)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                "AsterionDownloader/$folder/$filename")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            SiteSession.cookie(item.url)?.let{addRequestHeader("Cookie",it)}
        }
        manager.enqueue(req)>0L
    }.getOrDefault(false)
    fun status(context:Context,area:String):List<IndexedSource> =
        SourceIndexDb(context).use{it.sources().filter{src->src.area==area}}
    fun items(context:Context,area:String?=null,limit:Int=40):List<IndexedItem> =
        SourceIndexDb(context).use{it.items(area,limit)}
}

class SourceIndexJob:JobService() {
    private val stopped=AtomicBoolean(false)
    override fun onStartJob(params:JobParameters):Boolean {
        stopped.set(false)
        thread(name="asterion-source-index-job"){
            try{SourceIndex.scan(applicationContext){stopped.get()}}
            finally{jobFinished(params,false)}
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean{
        stopped.set(true)
        return true
    }
}
