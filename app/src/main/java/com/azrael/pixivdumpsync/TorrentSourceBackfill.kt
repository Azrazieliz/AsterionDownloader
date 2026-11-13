package com.azrael.pixivdumpsync

import android.content.ContentValues
import android.content.Context
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.azrael.galleryflow.HttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Dedicated historical torrent indexes: OneJAV and Sukebei.
 * The feed tracks actual torrent metadata and preserves pagination.
 * Selective payload transfers stay in the existing libtorrent file chooser.
 */
data class TorrentFeedEntry(val source:String,val title:String,val page:String,
    val download:String?,val discovered:Long,val state:String)
data class TorrentFeedStatus(val source:String,val cursor:String?,
    val lastCheck:Long,val error:String?,val count:Int)

internal object TorrentFeedParser {
    val onejav="https://onejav.com/"
    val sukebei="https://sukebei.nyaa.si/"
    val nyaaMirror="https://nyaa.v300.eu.org/"
    fun home(source:String):String=when(source){
        "ONEJAV"->onejav
        "SUKEBEI"->sukebei
        "NYAA_MIRROR"->nyaaMirror
        else->error("Unknown torrent feed source")
    }
    fun host(source:String):String=URI(home(source)).host
    fun source(url:String):String?=runCatching{
        when(URI(url).host?.lowercase()?.removePrefix("www.")){
            "onejav.com"->"ONEJAV"
            "sukebei.nyaa.si"->"SUKEBEI"
            "nyaa.v300.eu.org"->"NYAA_MIRROR"
            else->null
        }
    }.getOrNull()
    fun entries(doc:Document,source:String):List<TorrentFeedEntry>{
        val host=host(source)
        val now=System.currentTimeMillis()
        val found=LinkedHashMap<String,TorrentFeedEntry>()
        val selector=if(source=="ONEJAV")
            "a[href*='/torrent/'],article a[href],.torrent-list a[href]"
        else "table a[href*='/view/'],.torrent-list a[href*='/view/']"
        for(a in doc.select(selector).take(4500)){
            val uri=runCatching{URI(doc.location()).resolve(a.attr("href"))}
                .getOrNull()?:continue
            if(uri.scheme!="https" || uri.userInfo!=null ||
                uri.host?.lowercase()?.removePrefix("www.")!=host)continue
            val valid=if(source=="ONEJAV")
                Regex("^/torrent/[A-Za-z0-9_-]{2,100}/?$").matches(uri.path.orEmpty())
            else Regex("^/view/[0-9]{1,20}/?$").matches(uri.path.orEmpty())
            if(!valid)continue
            val title=a.attr("title").ifBlank{a.text()}
                .ifBlank{a.selectFirst("img")?.attr("alt").orEmpty()}
                .ifBlank{a.closest("article,tr")?.selectFirst("h2,h3")?.text().orEmpty()}
                .trim().take(160)
            if(title.length<3)continue
            val row=a.closest("tr,article")
            val magnet=row?.selectFirst("a[href^=magnet:]")?.attr("href")
            val torrent=row?.selectFirst("a[href$=.torrent],a[href*='/download/']")?.absUrl("href")
            val candidate=validTransfer(magnet)?:
                torrent?.takeIf{validTransfer(it)!=null}
            found.putIfAbsent(uri.toString(),TorrentFeedEntry(source,title,
                uri.toString(),candidate,now,"DISCOVERED"))
        }
        return found.values.toList()
    }
    fun validTransfer(raw:String?):String? {
        if(raw.isNullOrBlank())return null
        if(raw.startsWith("magnet:?xt=urn:btih:",true) ||
            raw.startsWith("magnet:?xt=urn:btmh:",true))
            return raw.takeIf{it.length<6000}
        val uri=runCatching{URI(raw)}.getOrNull()?:return null
        if(uri.scheme!="https" || uri.userInfo!=null)return null
        val host=uri.host?.lowercase()?.removePrefix("www.")?:return null
        if(host !in setOf("onejav.com","sukebei.nyaa.si","nyaa.v300.eu.org"))
            return null
        return raw.takeIf{uri.path.orEmpty().endsWith(".torrent",true) ||
            Regex("""^/download/[0-9]{1,20}(?:\.torrent)?$""")
                .matches(uri.path.orEmpty())}
    }
    fun detail(doc:Document,source:String):String? {
        for(a in doc.select("a[href^=magnet:],a[href$=.torrent],"+
            "a[href*='/download/']")){
            val raw=a.attr("href")
            val target=if(raw.startsWith("magnet:"))raw
                else runCatching{URI(doc.location()).resolve(raw).toString()}
                    .getOrNull()?:continue
            validTransfer(target)?.let{return it}
        }
        return null
    }
    private fun pageNumber(url:String):Int{
        val uri=runCatching{URI(url)}.getOrNull()?:return 1
        val query=uri.rawQuery.orEmpty().split('&')
        for(entry in query){
            if(entry.substringBefore('=') in setOf("page","p")){
                val number=entry.substringAfter('=').toIntOrNull()
                if(number!=null && number in 1..1_000_000)return number
            }
        }
        return Regex("""/(?:page|paged)/([0-9]{1,7})/?$""")
            .find(uri.path.orEmpty())?.groupValues?.getOrNull(1)
            ?.toIntOrNull()?:1
    }
    fun next(doc:Document,source:String,current:String):String? {
        val host=host(source)
        val currentNumber=pageNumber(current)
        val choices=ArrayList<Pair<Int,String>>()
        val next=doc.select("a[rel=next][href],a.pagination-next[href],"+
            "a.next[href],.pagination a.next[href],"+
            ".pagination a[aria-label=Next][href],"+
            ".pagination a[href*='p='],.pagination a[href*='page='], "+
            ".pagination a[href*='/page/']")
        for(a in next){
            val u=runCatching{URI(doc.location()).resolve(a.attr("href"))}
                .getOrNull()?:continue
            if(u.scheme!="https" || u.userInfo!=null ||
                u.host?.lowercase()?.removePrefix("www.")!=host ||
                u.toString()==current || !SourceIndexParser.safe(u.toString()))
                continue
            val page=pageNumber(u.toString())
            if(page>currentNumber && page<=currentNumber+50)
                choices+=page to u.toString()
        }
        return choices.minByOrNull{it.first}?.second
    }

}

class TorrentFeedDb(ctx:Context):
    SQLiteOpenHelper(ctx,"asterion_torrent_feed_sources.sqlite",null,1){
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("""CREATE TABLE feeds(
            source TEXT PRIMARY KEY,cursor TEXT,last_check INTEGER NOT NULL DEFAULT 0,
            error TEXT)""")
        db.execSQL("""CREATE TABLE entries(
            page TEXT PRIMARY KEY,source TEXT NOT NULL,title TEXT NOT NULL,
            target TEXT,discovered INTEGER NOT NULL,
            state TEXT NOT NULL DEFAULT 'DISCOVERED')""")
        db.execSQL("CREATE INDEX torrent_feed_latest ON entries(source,discovered)")
        db.execSQL("CREATE INDEX torrent_feed_state ON entries(source,state)")
    }
    override fun onUpgrade(db:SQLiteDatabase,old:Int,next:Int){}
    fun enroll(source:String){
        require(source in setOf("ONEJAV","SUKEBEI","NYAA_MIRROR"))
        writableDatabase.insertWithOnConflict("feeds",null,
            ContentValues().apply{put("source",source)},
            SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun status():List<TorrentFeedStatus> {
        val out=ArrayList<TorrentFeedStatus>()
        for(source in listOf("ONEJAV","SUKEBEI","NYAA_MIRROR")){
            val record=readableDatabase.rawQuery(
                "SELECT cursor,last_check,error FROM feeds WHERE source=?",
                arrayOf(source)).use{c->
                if(c.moveToFirst())Triple(c.getString(0),c.getLong(1),c.getString(2))
                else Triple(null,0L,null)
            }
            val count=readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM entries WHERE source=?",
                arrayOf(source)).use{c->if(c.moveToFirst())c.getInt(0)else 0}
            out+=TorrentFeedStatus(source,record.first,record.second,record.third,count)
        }
        return out
    }
    fun record(item:TorrentFeedEntry) {
        val values=ContentValues().apply{
            put("page",item.page);put("source",item.source)
            put("title",item.title);item.download?.let{put("target",it)}
            put("discovered",item.discovered);put("state",item.state)
        }
        writableDatabase.insertWithOnConflict("entries",null,values,
            SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun discovered(source:String,limit:Int=120):List<TorrentFeedEntry>{
        val out=ArrayList<TorrentFeedEntry>()
        readableDatabase.rawQuery(
            "SELECT source,title,page,target,discovered,state FROM entries "+
            "WHERE source=? ORDER BY discovered DESC LIMIT ?",
            arrayOf(source,limit.coerceIn(1,300).toString())).use{c->
            while(c.moveToNext())out+=TorrentFeedEntry(c.getString(0),c.getString(1),
                c.getString(2),c.getString(3),c.getLong(4),c.getString(5))
        }
        return out
    }
    fun resolved(page:String,target:String) {
        writableDatabase.update("entries",ContentValues().apply{
            put("target",target)
        },"page=?",arrayOf(page))
    }
    fun checked(source:String,next:String?,error:String?=null){
        writableDatabase.insertWithOnConflict("feeds",null,
            ContentValues().apply{put("source",source)},
            SQLiteDatabase.CONFLICT_IGNORE)
        writableDatabase.update("feeds",ContentValues().apply{
            if(next==null)putNull("cursor") else put("cursor",next)
            put("last_check",System.currentTimeMillis())
            if(error==null)putNull("error") else put("error",error.take(250))
        },"source=?",arrayOf(source))
    }
    fun failure(source:String,error:String){
        writableDatabase.insertWithOnConflict("feeds",null,
            ContentValues().apply{put("source",source)},
            SQLiteDatabase.CONFLICT_IGNORE)
        writableDatabase.update("feeds",ContentValues().apply{
            put("error",error.take(250));put("last_check",System.currentTimeMillis())
        },"source=?",arrayOf(source))
    }
}

object TorrentSourceBackfill {
    private const val PERIODIC=49898
    private const val CATCHUP=49899
    private val busy=AtomicBoolean(false)
    fun ensure(ctx:Context){
        if(!AppPower.enabled(ctx))return
        val jobs=ctx.getSystemService(JobScheduler::class.java)
        if(jobs.allPendingJobs.none{it.id==PERIODIC}){
            jobs.schedule(JobInfo.Builder(PERIODIC,
                ComponentName(ctx,TorrentFeedJob::class.java))
                .setPersisted(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(6L*60L*60L*1000L).build())
        }
        if(TorrentFeedDb(ctx).use{db->db.status().any{it.cursor!=null}})
            scheduleCatchup(ctx)
    }
    fun scheduleCatchup(ctx:Context){
        if(!AppPower.enabled(ctx))return
        val jobs=ctx.getSystemService(JobScheduler::class.java)
        if(jobs.allPendingJobs.any{it.id==CATCHUP})return
        jobs.schedule(JobInfo.Builder(CATCHUP,
            ComponentName(ctx,TorrentFeedJob::class.java))
            .setPersisted(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(120_000L)
            .setOverrideDeadline(6L*60L*1000L).build())
    }
    fun scanAsync(ctx:Context){
        thread(name="asterion-torrent-feed"){
            scan(ctx.applicationContext)
        }
    }
    private fun document(ctx:Context,url:String):Document {
        require(TorrentFeedParser.source(url)!=null)
        val response=Jsoup.connect(url).userAgent(HttpClient.USER_AGENT)
            .timeout(20000).maxBodySize(8_000_000)
            .followRedirects(false).ignoreHttpErrors(true)
            .apply {
                com.asterion.videoflow.NetworkRoute.javaProxy(ctx,url)
                    ?.let { proxy(it) }
                com.azrael.galleryflow.SiteSession.cookie(url)
                    ?.let { header("Cookie",it) }
            }.execute()
        if(response.statusCode() in setOf(401,403) &&
            com.azrael.galleryflow.SiteSession.bound(ctx,url)){
            // Preserve only the user's legitimate authenticated browser
            // session. No fabricated torrent cards or protection bypass.
            val html=BrowserPageFetch.html(ctx,url)
            return Jsoup.parse(html,url)
        }
        require(response.statusCode() in 200..299){
            "Torrent listing HTTP "+response.statusCode()+
                "; source access blocked, connect your authorized account"
        }
        return response.parse()
    }
    fun resolve(ctx:Context,page:String):String?{
        if(TorrentFeedParser.source(page)==null)return null
        val doc=document(ctx,page)
        val src=TorrentFeedParser.source(page)?:return null
        return TorrentFeedParser.detail(doc,src)?.also{
            TorrentFeedDb(ctx).use{db->db.resolved(page,it)}
        }
    }
    private val active=java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.atomic.AtomicBoolean>()
    private val starting=java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.atomic.AtomicBoolean>()
    fun isRunning(source:String):Boolean=
        active.containsKey(source)||starting.containsKey(source)
    fun stopSite(source:String):Boolean{
        val token=active[source]?:starting[source]?:return false
        token.set(true)
        starting[source]?.set(true)
        return true
    }
    fun startSite(ctx:Context,source:String):Boolean{
        if(!AppPower.enabled(ctx) || TorrentFeedParser.source(
            TorrentFeedParser.home(source))!=source)return false
        val token=java.util.concurrent.atomic.AtomicBoolean(false)
        if(active.containsKey(source) ||
            starting.putIfAbsent(source,token)!=null)return false
        thread(name="asterion-torrent-"+source.lowercase()){
            try{
                scan(ctx.applicationContext,stop={token.get()},onlySource=source)
            }finally{starting.remove(source,token)}
        }
        return true
    }
    fun scan(ctx:Context,stop:()->Boolean={false},onlySource:String?=null) {
        if(!AppPower.enabled(ctx))return
        val sources=listOf("ONEJAV","SUKEBEI","NYAA_MIRROR")
            .filter{onlySource==null||it==onlySource}
        try{TorrentFeedDb(ctx).use{db->
            for(site in sources){
                if(onlySource==null && !SiteControls.sync(ctx,
                        TorrentFeedParser.home(site)))continue
                val token=java.util.concurrent.atomic.AtomicBoolean(false)
                if(active.putIfAbsent(site,token)!=null)continue
                try{
                val state=db.status().first{it.source==site}
                val home=TorrentFeedParser.home(site)
                val queue=ArrayDeque<String>()
                queue.addLast(home)
                state.cursor?.let{queue.addLast(it)}
                val seen=HashSet<String>()
                var budget=if(state.cursor==null)2 else 6
                while(queue.isNotEmpty() && budget-->0 && !stop() &&
                    !token.get() && AppPower.enabled(ctx)){
                    val current=queue.removeFirst()
                    if(!seen.add(current))continue
                    try{
                        val doc=document(ctx,current)
                        val entries=TorrentFeedParser.entries(doc,site)
                        // HTTP 200 can still be a JS/age-gate shell.
                        // Do not advance OneJAV's historical cursor or call
                        // its nonexistent torrent list a completed scan.
                        if(site=="ONEJAV" && current==home && entries.isEmpty())
                            error("OneJAV returned no public torrent cards; "+
                                "connect the website account or provider adapter needs repair")
                        entries.forEach(db::record)
                        val next=TorrentFeedParser.next(doc,site,current)
                        db.checked(site,next)
                        if(next!=null && next !in seen)queue.addLast(next)
                    }catch(t:Exception){
                        db.failure(site,t.message?:t.javaClass.simpleName)
                        break
                    }
                }
                }finally{active.remove(site)}
            }
        }}finally{
            if(onlySource==null && !stop() && AppPower.enabled(ctx) &&
                TorrentFeedDb(ctx).use{db->db.status().any{it.cursor!=null}})
                scheduleCatchup(ctx)
        }
    }
}
class TorrentFeedJob:JobService(){
    private val stopped=AtomicBoolean(false)
    override fun onStartJob(params:JobParameters):Boolean{
        stopped.set(false)
        thread(name="asterion-torrent-history"){
            try{TorrentSourceBackfill.scan(applicationContext,stop={stopped.get()})}
            finally{jobFinished(params,false)}
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean{
        stopped.set(true)
        return true
    }
}
