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
import com.azrael.galleryflow.HttpClient
import com.asterion.videoflow.VideoDb
import com.asterion.videoflow.VideoEngine
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class IndexedSource(val url:String,val name:String,val area:String,val enabled:Boolean,
    val cursor:String?,val lastCheck:Long,val error:String?,val discovered:Int,
    val adapter:String,val historyComplete:Boolean=false)
data class IndexedItem(val url:String,val title:String,val preview:String?,val source:String,
    val area:String,val kind:String,val discoveredAt:Long,val queued:Boolean)

class SourceIndexDb(context:Context):
    SQLiteOpenHelper(context,"asterion_source_index.sqlite",null,6) {
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("""CREATE TABLE sources(
            url TEXT PRIMARY KEY,name TEXT NOT NULL,area TEXT NOT NULL,
            enabled INTEGER NOT NULL DEFAULT 1,cursor TEXT,last_check INTEGER NOT NULL DEFAULT 0,
            error TEXT,discovered INTEGER NOT NULL DEFAULT 0,
            adapter TEXT NOT NULL DEFAULT 'generic',
            history_complete INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("""CREATE TABLE entries(
            url TEXT PRIMARY KEY,source TEXT NOT NULL,title TEXT NOT NULL,preview TEXT,
            area TEXT NOT NULL,kind TEXT NOT NULL,discovered_at INTEGER NOT NULL,
            queued INTEGER NOT NULL DEFAULT 0,
            attempts INTEGER NOT NULL DEFAULT 0,
            retry_after INTEGER NOT NULL DEFAULT 0,
            dispatch_until INTEGER NOT NULL DEFAULT 0,
            detail_checked INTEGER NOT NULL DEFAULT 0,
            detail_attempts INTEGER NOT NULL DEFAULT 0,
            detail_retry_after INTEGER NOT NULL DEFAULT 0,
            detail_error TEXT)""")
        db.execSQL("CREATE INDEX entries_source ON entries(source,discovered_at)")
        db.execSQL("CREATE INDEX entries_area ON entries(area,discovered_at)")
        db.execSQL("CREATE INDEX entries_detail_queue ON entries(detail_checked,detail_retry_after,discovered_at)")
        db.execSQL("CREATE INDEX entries_original_queue ON entries(queued,retry_after,discovered_at)")
    }
    override fun onUpgrade(db:SQLiteDatabase,a:Int,b:Int){
        if(a<2){
            db.execSQL("ALTER TABLE entries ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE entries ADD COLUMN retry_after INTEGER NOT NULL DEFAULT 0")
        }
        if(a<3){
            db.execSQL("ALTER TABLE entries ADD COLUMN detail_checked INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE entries ADD COLUMN detail_attempts INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE entries ADD COLUMN detail_retry_after INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE entries ADD COLUMN detail_error TEXT")
        }
        if(a<4){
            db.execSQL("CREATE INDEX IF NOT EXISTS entries_detail_queue ON entries(detail_checked,detail_retry_after,discovered_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS entries_original_queue ON entries(queued,retry_after,discovered_at)")
        }
        if(a<5)db.execSQL("ALTER TABLE sources ADD COLUMN history_complete INTEGER NOT NULL DEFAULT 0")
        if(a<6)db.execSQL(
            "ALTER TABLE entries ADD COLUMN dispatch_until INTEGER NOT NULL DEFAULT 0")
    }
    fun add(url:String,name:String,area:String,adapter:String="generic"){
        val v=ContentValues().apply{
            put("url",url);put("name",name);put("area",area);put("adapter",adapter)
        }
        writableDatabase.insertWithOnConflict("sources",null,v,SQLiteDatabase.CONFLICT_IGNORE)
    }
    /** Promote supported torrent feeds into their own history engine without
     * resetting user's include/exclude preference or saved source metadata.
     */
    /** Preserve subscribed source identity and every discovered media row when
     * a provider officially migrates its domain (OMG.xxx -> OMG.porn).
     * Never auto-migrate to an unrelated redirect destination.
     */
    fun migrateOfficialSource(oldUrl:String,newUrl:String){
        if(oldUrl==newUrl || !SourceIndexParser.safe(oldUrl) ||
            !SourceIndexParser.safe(newUrl))return
        val db=writableDatabase
        db.beginTransactionNonExclusive()
        try{
            val old=db.rawQuery(
                "SELECT name,area,enabled,cursor,last_check,error,discovered,"+
                    "adapter,history_complete FROM sources WHERE url=?",
                arrayOf(oldUrl)).use{q->
                if(q.moveToFirst())List(9){n->
                    if(q.isNull(n))null else q.getString(n)
                }else null
            }?:run{db.setTransactionSuccessful();return}
            val newExists=db.rawQuery(
                "SELECT 1 FROM sources WHERE url=?",arrayOf(newUrl))
                .use{it.moveToFirst()}
            if(!newExists){
                db.execSQL("UPDATE sources SET url=? WHERE url=?",
                    arrayOf(newUrl,oldUrl))
            }else{
                db.execSQL(
                    "UPDATE sources SET enabled=?,cursor=COALESCE(cursor,?),"+
                    "last_check=MAX(last_check,?),"+
                    "discovered=MAX(discovered,?),"+
                    "history_complete=MAX(history_complete,?) WHERE url=?",
                    arrayOf(old[2]?: "1",old[3],old[4]?: "0",
                        old[6]?: "0",old[8]?: "0",newUrl))
                db.delete("sources","url=?",arrayOf(oldUrl))
            }
            db.execSQL("UPDATE entries SET source=? WHERE source=?",
                arrayOf(newUrl,oldUrl))
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }

    fun setAdapter(url:String,adapter:String){
        require(adapter in setOf("native","generic"))
        writableDatabase.update("sources",ContentValues().apply{
            put("adapter",adapter)
        },"url=?",arrayOf(url))
    }
    fun enable(url:String,value:Boolean){
        val v=ContentValues().apply{put("enabled",if(value)1 else 0)}
        writableDatabase.update("sources",v,"url=?",arrayOf(url))
    }
    fun reset(url:String){
        writableDatabase.execSQL("UPDATE sources SET cursor=NULL,error=NULL,history_complete=0 WHERE url=?",arrayOf(url))
    }
    fun update(url:String,cursor:String?,error:String?,count:Int,
               historyComplete:Boolean?=null){
        val v=ContentValues().apply{
            if(cursor==null)putNull("cursor") else put("cursor",cursor)
            if(error==null)putNull("error") else put("error",error.take(280))
            put("last_check",System.currentTimeMillis())
            put("discovered",count)
            if(historyComplete!=null)put("history_complete",if(historyComplete)1 else 0)
        }
        writableDatabase.update("sources",v,"url=?",arrayOf(url))
    }
    /** A failed page must never roll back the last successful backfill cursor/count. */
    fun failure(url:String,reason:String){
        val v=ContentValues().apply{
            put("error",reason.take(280))
            put("last_check",System.currentTimeMillis())
        }
        writableDatabase.update("sources",v,"url=?",arrayOf(url))
    }
    fun sources():List<IndexedSource>{
        val out=ArrayList<IndexedSource>()
        readableDatabase.rawQuery("""SELECT url,name,area,enabled,cursor,last_check,error,
                discovered,adapter,history_complete FROM sources ORDER BY area,name COLLATE NOCASE""",null).use{c->
            while(c.moveToNext())out+=IndexedSource(c.getString(0),c.getString(1),
                c.getString(2),c.getInt(3)==1,c.getString(4),c.getLong(5),
                c.getString(6),c.getInt(7),c.getString(8),c.getInt(9)==1)
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
        val db=writableDatabase
        val inserted=db.insertWithOnConflict("entries",null,v,
            SQLiteDatabase.CONFLICT_IGNORE)!=-1L
        if(!inserted){
            // Existing cards can gain a real preview on later scans; retain original
            // queue state and discovery order instead of incorrectly resetting them.
            val refreshed=ContentValues().apply{
                put("title",item.title.take(220))
                if(!item.preview.isNullOrBlank())put("preview",item.preview)
            }
            db.update("entries",refreshed,"url=?",arrayOf(item.url))
        }
        return inserted
    }
    fun markQueued(url:String){
        val v=ContentValues().apply{
            put("queued",1);put("attempts",0);put("retry_after",0)
        }
        writableDatabase.update("entries",v,"url=?",arrayOf(url))
    }
    /** Track detail pages separately from direct originals. Network or provider
     * failures do not advance the detail cursor or silently discard attachments.
     * Scans remain bounded even for collections with hundreds of thousands of posts.
     */
    fun pendingDetails(limit:Int=12):List<IndexedItem>{
        val out=ArrayList<IndexedItem>()
        readableDatabase.rawQuery(
            """SELECT e.url,e.title,e.preview,e.source,e.area,e.kind,e.discovered_at,e.queued
               FROM entries e JOIN sources s ON e.source=s.url
               WHERE e.detail_checked=0 AND e.detail_retry_after<=?
                 AND s.enabled=1 AND s.adapter='generic'
                 AND e.area IN ('BOOKS','COMICS','GAMES','ASSETS',
                     'VIDEOS','ADULT','HENTAI','CREATORS','COSPLAY','ARTWORK',
                     'STUDIO','MMD','LIVE2D','ANIMATION','MODS','VOICE')
                 AND e.kind NOT IN ('epub','archive','apk','model','video',
                     'image','torrent','original','creative','mod','audio')
               ORDER BY e.discovered_at ASC LIMIT ?""",
            arrayOf(System.currentTimeMillis().toString(),
                limit.coerceIn(1,40).toString())).use{c->
            while(c.moveToNext())out+=IndexedItem(c.getString(0),c.getString(1),
                c.getString(2),c.getString(3),c.getString(4),c.getString(5),
                c.getLong(6),c.getInt(7)==1)
        }
        return out
    }
    fun detailsFinished(url:String){
        writableDatabase.update("entries",ContentValues().apply{
            put("detail_checked",1);putNull("detail_error")
            put("detail_retry_after",0)
        },"url=?",arrayOf(url))
    }
    fun detailsFailed(url:String,reason:String){
        readableDatabase.rawQuery("SELECT detail_attempts FROM entries WHERE url=?",
            arrayOf(url)).use{c->
            if(!c.moveToFirst())return
            val n=c.getInt(0).coerceIn(0,12)
            val wait=(60_000L shl n.coerceAtMost(8))
                .coerceAtMost(12L*60L*60_000L)
            writableDatabase.update("entries",ContentValues().apply{
                put("detail_attempts",n+1)
                put("detail_retry_after",System.currentTimeMillis()+wait)
                put("detail_error",reason.take(280))
            },"url=?",arrayOf(url))
        }
    }
    /** A delayed failed detail is not immediately runnable. Repeatedly
     * scheduling it before the retry deadline wastes battery and network.
     */
    fun hasPendingDetails():Boolean=
        readableDatabase.rawQuery(
            "SELECT 1 FROM entries e JOIN sources s ON e.source=s.url "+
                "WHERE e.detail_checked=0 AND e.detail_retry_after<=? "+
                "AND s.enabled=1 AND s.adapter='generic' AND " +
                "e.area IN ('BOOKS','COMICS','GAMES','ASSETS','VIDEOS','ADULT','HENTAI','CREATORS','COSPLAY','ARTWORK','STUDIO','MMD','LIVE2D','ANIMATION','MODS','VOICE') "+
                "AND e.kind NOT IN ('epub','archive','apk','model','video','image','torrent','original','creative','mod','audio') LIMIT 1",
            arrayOf(System.currentTimeMillis().toString())).use{it.moveToFirst()}

    fun nextDetailRetryMs():Long?{
        val now=System.currentTimeMillis()
        val first=readableDatabase.rawQuery(
            "SELECT MIN(e.detail_retry_after) FROM entries e "+
                "JOIN sources s ON e.source=s.url "+
                "WHERE e.detail_checked=0 AND e.detail_retry_after>? "+
                "AND s.enabled=1 AND s.adapter='generic' AND "+
                "e.area IN ('BOOKS','COMICS','GAMES','ASSETS','VIDEOS','ADULT','HENTAI','CREATORS','COSPLAY','ARTWORK','STUDIO','MMD','LIVE2D','ANIMATION','MODS','VOICE') "+
                "AND e.kind NOT IN ('epub','archive','apk','model','video','image','torrent','original','creative','mod','audio')",
            arrayOf(now.toString())).use{cursor->
            if(cursor.moveToFirst() && !cursor.isNull(0))cursor.getLong(0) else null
        }
        return first?.let{(it-now).coerceAtLeast(1000L)}
    }

    /** A deferred original should wake at its recorded deadline, not wait up
     * to six hours for the next provider-wide index scan.
     */
    fun nextDirectRetryMs():Long?{
        val now=System.currentTimeMillis()
        val first=readableDatabase.rawQuery(
            "SELECT MIN(e.retry_after) FROM entries e "+
                "JOIN sources s ON e.source=s.url "+
                "WHERE e.queued=0 AND e.retry_after>? AND s.enabled=1 "+
                "AND e.kind IN ('video','epub','original','model','archive','apk','books','comics','creative','mod','audio')",
            arrayOf(now.toString())).use{cursor->
            if(cursor.moveToFirst() && !cursor.isNull(0))cursor.getLong(0) else null
        }
        return first?.let{(it-now).coerceAtLeast(1000L)}
    }
    /** Retry only direct originals not yet accepted by their native download
     * engine. Historical pages do not have to be revisited just to retry a URL.
     */
    fun pendingDirect(limit:Int=30,area:String?=null,offset:Int=0):List<IndexedItem>{
        val rows=ArrayList<IndexedItem>()
        readableDatabase.rawQuery(
            """SELECT url,title,preview,source,area,kind,discovered_at,queued
                FROM entries WHERE queued=0 AND retry_after<=? AND dispatch_until<=? AND
                    (?='' OR area=?) AND EXISTS (SELECT 1 FROM sources src WHERE src.url=entries.source
                        AND src.enabled=1) AND (
                    (kind='video' AND area IN ('VIDEOS','ADULT','HENTAI','CREATORS'))
                    OR (kind='epub' AND area='BOOKS')
                    OR (kind='books' AND area='BOOKS' AND
                        (url LIKE 'https://freewebnovel.com/%' OR
                         url LIKE 'https://www.freewebnovel.com/%' OR
                         url LIKE 'https://novelbuddy.com/%' OR
                         url LIKE 'https://www.novelbuddy.com/%' OR
                         url LIKE 'https://novelbuddy.me/%' OR
                         url LIKE 'https://www.novelbuddy.me/%' OR
                         url LIKE 'https://readnovelfull.com/%' OR
                         url LIKE 'https://www.readnovelfull.com/%') AND
                        (url LIKE '%/novel/%' OR url LIKE '%/book/%') AND
                        url NOT LIKE '%/chapter-%' AND url NOT LIKE '%/chapitre-%')
                    OR (kind='original' AND area IN ('CREATORS','COSPLAY','ARTWORK'))
                    OR (kind IN ('model','creative','archive','original') AND
                        area IN ('STUDIO','MMD','LIVE2D','ANIMATION'))
                    OR (kind IN ('mod','archive','apk','original') AND area='MODS')
                    OR (kind IN ('audio','archive','original') AND area='VOICE')
                    OR (kind='model' AND area='ASSETS')
                    OR (kind='comics' AND area='COMICS' AND
                        (url LIKE 'https://nhentai.net/g/%' OR
                         url LIKE 'https://nhentai.xxx/g/%' OR
                         url LIKE 'https://sushiscan.net/catalogue/%'))
                    OR (kind='archive' AND area='COMICS' AND
                        (url LIKE '%.zip' OR url LIKE '%.zip?%'))
                    OR (kind='archive' AND area IN ('GAMES','ASSETS'))
                    OR (kind='apk' AND area='GAMES')
                )
                ORDER BY discovered_at ASC LIMIT ? OFFSET ?""",
            arrayOf(System.currentTimeMillis().toString(),
                System.currentTimeMillis().toString(),area.orEmpty(),area.orEmpty(),
                limit.coerceIn(1,120).toString(),offset.coerceAtLeast(0).toString())).use{c->
            while(c.moveToNext())rows+=IndexedItem(c.getString(0),c.getString(1),
                c.getString(2),c.getString(3),c.getString(4),c.getString(5),
                c.getLong(6),c.getInt(7)==1)
        }
        return rows
    }
    /** Acquire exclusive enqueue ownership before either module agent
     * submits a direct original. SQLite conditional UPDATE prevents duplicate
     * requests across threads; expired claims recover after worker crashes.
     */
    fun claimDirect(url:String):Boolean{
        val now=System.currentTimeMillis()
        val v=ContentValues().apply{put("dispatch_until",now+120_000L)}
        return writableDatabase.update("entries",v,
            "url=? AND queued=0 AND retry_after<=? AND dispatch_until<=?",
            arrayOf(url,now.toString(),now.toString()))==1
    }
    fun releaseDirect(url:String){
        writableDatabase.update("entries",ContentValues().apply{
            put("dispatch_until",0L)
        },"url=?",arrayOf(url))
    }
    /** Only retry items whose persisted backoff has expired. */
    fun hasPendingDirect():Boolean=pendingDirect(1).isNotEmpty()
    fun defer(url:String){
        readableDatabase.rawQuery("SELECT attempts FROM entries WHERE url=?",
            arrayOf(url)).use{c->
            if(!c.moveToFirst())return
            val n=c.getInt(0).coerceIn(0,10)
            val backoff=(60_000L shl n.coerceAtMost(7))
                .coerceAtMost(6L*60*60_000L)
            val cv=ContentValues().apply{
                put("attempts",n+1)
                put("retry_after",System.currentTimeMillis()+backoff)
            }
            writableDatabase.update("entries",cv,"url=?",arrayOf(url))
        }
    }
    fun forSource(source:String,limit:Int=48):List<IndexedItem>{
        val out=ArrayList<IndexedItem>()
        readableDatabase.rawQuery(
            "SELECT url,title,preview,source,area,kind,discovered_at,queued "+
              "FROM entries WHERE source=? ORDER BY discovered_at DESC LIMIT ?",
            arrayOf(source,limit.coerceIn(1,300).toString())
        ).use{c->
            while(c.moveToNext())out+=IndexedItem(c.getString(0),c.getString(1),
                c.getString(2),c.getString(3),c.getString(4),c.getString(5),
                c.getLong(6),c.getInt(7)==1)
        }
        return out
    }
    fun pendingForSource(source:String,details:Boolean,limit:Int=30):List<IndexedItem>{
        val sql=if(details)
            "SELECT url,title,preview,source,area,kind,discovered_at,queued "+
                "FROM entries WHERE source=? AND detail_checked=0 AND detail_retry_after<=? "+
                "AND kind NOT IN ('epub','archive','apk','model','video','image',"+
                "'torrent','original','creative','mod','audio') "+
                "ORDER BY discovered_at ASC LIMIT ?"
        else
            "SELECT url,title,preview,source,area,kind,discovered_at,queued "+
                "FROM entries WHERE source=? AND queued=0 AND retry_after<=? "+
                "AND kind IN ('epub','archive','apk','model','video',"+
                "'torrent','original','creative','mod','audio','books','comics') "+
                "ORDER BY discovered_at ASC LIMIT ?"
        val out=ArrayList<IndexedItem>()
        readableDatabase.rawQuery(sql,
            arrayOf(source,System.currentTimeMillis().toString(),
                limit.coerceIn(1,60).toString())).use{c->
            while(c.moveToNext())out+=IndexedItem(c.getString(0),c.getString(1),
                c.getString(2),c.getString(3),c.getString(4),c.getString(5),
                c.getLong(6),c.getInt(7)==1)
        }
        return out
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
    private val media=Regex("(?i)\\.(?:mp4|m4v|webm|mkv|mov|epub|zip|7z|rar|blend|fbx|obj|glb|gltf|stl|apk|torrent|png|jpg|jpeg|webp|gif|avif|pmx|pmd|vmd|vpd|vrm|moc3|psd|kra|clip|unitypackage|pak|xp3|rpa|rpy|mp3|flac|m4a|wav|ogg|opus|model3\\.json|motion3\\.json|physics3\\.json)(?:[?#]|$)")
    internal fun safe(url:String):Boolean=runCatching{
        val uri=URI(url)
        uri.scheme=="https" && SearchRanking.validUrl(url) &&
            !uri.host.isNullOrBlank() && !uri.host.contains(":") &&
            !uri.host.endsWith(".internal",true) && uri.userInfo==null
    }.getOrDefault(false)
    /** Allow common www/mobile/locale redirects without sending cookies to a foreign site. */
    private fun host(url:String):String=runCatching{
        var name=URI(url).host.lowercase(Locale.ROOT)
        while(listOf("www.","m.","fr.","en.").any{name.startsWith(it)}){
            name=listOf("www.","m.","fr.","en.").firstOrNull{name.startsWith(it)}
                ?.let{name.removePrefix(it)}?:name
        }
        when(name){"omg.xxx"->"omg.porn";"novelbuddy.com"->"novelbuddy.me";else->name}
    }.getOrDefault("")
    internal fun sameSite(a:String,b:String):Boolean{
        val x=host(a)
        val y=host(b)
        return x.isNotBlank() && y.isNotBlank() &&
            (x==y || x.endsWith("."+y) || y.endsWith("."+x))
    }
    internal fun kind(url:String,area:String):String {
        val path=runCatching{URI(url).path.lowercase()}.getOrDefault("")
        return when{
            path.endsWith(".epub")->"epub"
            path.endsWith(".torrent")->"torrent"
            path.endsWith(".apk")->"apk"
            listOf(".blend",".fbx",".obj",".glb",".gltf",".stl",
                ".pmx",".pmd",".vmd",".vpd",".vrm").any{path.endsWith(it)}->"model"
            listOf(".psd",".kra",".clip",".moc3",".model3.json",".motion3.json",
                ".physics3.json").any{path.endsWith(it)}->"creative"
            listOf(".unitypackage",".pak",".xp3",".rpa",".rpy").any{path.endsWith(it)}->"mod"
            listOf(".mp3",".flac",".m4a",".wav",".ogg",".opus").any{path.endsWith(it)}->"audio"
            listOf(".mp4",".m4v",".webm",".mkv",".mov").any{path.endsWith(it)}->"video"
            listOf(".png",".jpg",".jpeg",".webp",".gif",".avif").any{path.endsWith(it)}->"image"
            listOf(".zip",".7z",".rar").any{path.endsWith(it)}->"archive"
            else->area.lowercase(Locale.ROOT)
        }
    }
    /** Exact original formats for auto-acquisition. Never pass page metadata or
     * thumbnails to a generic download: native provider adapters resolve those.
     * Books remain EPUB-only; comics remain ZIP-only; videos use VideoFlow.
     */
    internal fun autoOriginal(item:IndexedItem):Boolean=when(item.area){
        "BOOKS"->item.kind=="epub" ||
            (item.kind=="books" && NovelEpubParser.supportedSeries(item.url))
        "COMICS"->(item.kind=="archive" &&
            runCatching{URI(item.url).path.endsWith(".zip",true)}.getOrDefault(false)) ||
            (item.kind=="comics" && (NhentaiChapters.isGallery(item.url) ||
                SushiScanChapters.isSeries(item.url)))
        "GAMES"->item.kind in setOf("apk","archive")
        "ASSETS"->item.kind in setOf("model","archive")
        "STUDIO","MMD","LIVE2D","ANIMATION"->item.kind in
            setOf("model","creative","archive","original")
        "MODS"->item.kind in setOf("mod","archive","apk","original")
        "VOICE"->item.kind in setOf("audio","archive","original")
        "CREATORS"->item.kind in setOf("video","original")
        "COSPLAY","ARTWORK"->item.kind=="original"
        "VIDEOS","ADULT","HENTAI"->item.kind=="video"
        else->false
    }
    private fun preview(link:Element):String?{
        val image=link.selectFirst("img")?:link.parent()?.selectFirst("img")?:return null
        return SitePreviewImage.best(image)
    }
    internal fun entries(doc:Document,base:String,area:String,clock:Long):List<IndexedItem>{
        // Prefer a validated source-specific listing path over arbitrary
        // navigation links. Generic indexing remains for other providers.
        val dedicated=SiteBackfillProfiles.entries(doc,base,area,clock)
        if(!dedicated.isNullOrEmpty())return dedicated.filter{
            IndexedMediaQuality.isMedia(it.url,it.title,it.preview,area)
        }
        val creatorPosts=CreatorSiteBackfill.entries(doc,base,area,clock)
        if(!creatorPosts.isNullOrEmpty())return creatorPosts.filter{
            IndexedMediaQuality.isMedia(it.url,it.title,it.preview,area)
        }
        val out=LinkedHashMap<String,IndexedItem>()
        val scoped=doc.select("article a[href],main a[href],.post a[href],.posts a[href],"+
            ".gallery a[href],.entry a[href],.item a[href],.content a[href],"+
            ".video a[href],.thumb a[href],.video-preview-item a[href],"+
            ".card__cover a[href],.card__content a[href],.card__title a[href],"+
            "a[href*='/post/'],a[href*='/view/']")
        val candidates=if(scoped.isEmpty())doc.select("a[href]") else scoped
        for(link in candidates.take(8000)){
            val url=link.absUrl("href").substringBefore('#')
            if(!safe(url)||!sameSite(base,url)||junk.containsMatchIn(url))continue
            val path=URI(url).path.orEmpty()
            val direct=media.containsMatchIn(url)
            val knownVideo=area in setOf("ADULT","HENTAI","VIDEOS","CREATORS") &&
                com.asterion.videoflow.VideoSiteProfiles.isVideoPage(url)
            val detail=path.count{it=='/'}>=2 || path.endsWith(".html") ||
                path.contains("/view")||path.contains("/post")||path.contains("/watch") ||
                path.contains("/video")||path.contains("/product")||path.contains("/gallery")
            if(!direct && !detail && !knownVideo)continue
            val type=if(knownVideo)"video" else kind(url,area)
            if(area=="BOOKS" && type!="epub" && !(path.contains("novel")||path.contains("book")))continue
            if(area=="GAMES" && type !in listOf("torrent","apk","archive") &&
                !(path.contains("game")||path.contains("view")))continue
            val title=link.attr("title").ifBlank{link.text()}.ifBlank{
                link.selectFirst("img")?.attr("alt").orEmpty()
            }.trim().take(180)
            if(title.length<3 || IndexedMediaQuality.navigationTitle(title))continue
            val image=preview(link)?:link.closest(".card,.video-preview-item")
                ?.selectFirst("img")?.let{SitePreviewImage.best(it)}
            // Exact native video-page recognition is stronger evidence than
            // the generic multi-segment/thumbnail heuristic. JAVIDEO uses
            // localized /en/fc2ppv-... links and HentaiBros uses one slug.
            if(!IndexedMediaQuality.isMedia(url,title,image,area) &&
                !knownVideo)continue
            out.putIfAbsent(url,IndexedItem(url,title,image,base,area,type,clock,false))
            require(out.size<5000){
                "Provider page exceeds 5000 discovered originals; cursor is not advanced"
            }
        }
        // Some providers expose legitimate direct MP4/WebM originals in <video>
        // rather than <a>. Do not harvest arbitrary image thumbnails or trackers.
        if(area in setOf("ADULT","HENTAI","VIDEOS","CREATORS")){
            for(node in doc.select("article video[src],article video source[src],"+
                "main video[src],main video source[src],"+
                ".post video[src],.post video source[src]")){
                val url=node.absUrl("src").substringBefore('#')
                if(!safe(url) || kind(url,area)!="video" || url in out)continue
                val container=node.closest("article")?:node.closest(".post")
                val heading=container?.selectFirst("h1,h2,h3")?.text()
                val title=heading?.takeIf{it.length>=3}?:doc.title().takeIf{it.length>=3}
                    ?:URI(url).path.substringAfterLast('/')
                if(title.length<3)continue
                val cover=(node.closest("video")?:node).absUrl("poster")
                    .takeIf{safe(it)}
                out[url]=IndexedItem(url,title.take(180),cover,base,area,"video",
                    clock,false)
                require(out.size<5000){
                "Provider page exceeds 5000 discovered originals; cursor is not advanced"
            }
            }
        }
        require(candidates.size<=8000){
            "Provider listing has over 8000 links; requires paginated native adapter"
        }
        return out.values.toList()
    }
    /** Extract only files linked in the actual post content. A thumbnail, a meta
     * preview, or an arbitrary sidebar image is not an original download.
     * This is the conservative fallback for providers without a native API;
     * the native Pixiv/Kyora/VideoFlow/EPUB/ZIP engines retain ownership.
     */
    internal fun detailOriginals(doc:Document,source:IndexedItem,clock:Long):
            List<IndexedItem>{
        val content=doc.select("main a[href],article a[href],.post a[href],"+
            ".entry a[href],.attachments a[href],.download a[href],"+
            "a[download][href]")
        val results=LinkedHashMap<String,IndexedItem>()
        // Large gallery pages may contain hundreds of originals. Hitting the
        // bounded cap must be visible, not silently reported as full backfill.
        val maxOriginals=5000
        /** Creator image links can be thumbnails: accept non-video files only from
         * explicit attachments or download controls, never preview listings.
         */
        fun record(url:String,caption:String,explicitAttachment:Boolean,
                   filenameHint:String=""){
            if(!safe(url) || url==source.url)return
            val path=runCatching{URI(url).path.orEmpty().lowercase(Locale.ROOT)}
                .getOrDefault("")
            val basename=path.substringAfterLast('/')
            if(Regex("(?i)(?:^|[-_.])(preview|sample|teaser|thumb|thumbnail)(?:[-_.]|$)")
                .containsMatchIn(basename))return
            // Signed CDN endpoints often have no extension. Only an explicit
            // provider-supplied download filename may identify the file type.
            val pathType=kind(url,source.area)
            val type=if(explicitAttachment && filenameHint.isNotBlank() &&
                pathType==source.area.lowercase(Locale.ROOT))
                kind(filenameHint,source.area) else pathType
            val creatorOriginal=source.area=="CREATORS" && explicitAttachment &&
                type in setOf("image","archive","model","epub")
            val galleryOriginal=source.area in setOf("COSPLAY","ARTWORK") &&
                explicitAttachment && type in setOf("image","archive")
            val verticalOriginal=source.area in setOf(
                "STUDIO","MMD","LIVE2D","ANIMATION","MODS","VOICE") &&
                explicitAttachment && type in setOf(
                    "image","model","creative","mod","audio","archive","apk")
            val allowed=when(source.area){
                "BOOKS"->type=="epub"
                "COMICS"->type=="archive" &&
                    (path.endsWith(".zip") ||
                        filenameHint.endsWith(".zip",ignoreCase=true))
                "GAMES"->type in setOf("apk","archive")
                "ASSETS"->type in setOf("model","archive")
                "STUDIO","MMD","LIVE2D","ANIMATION"->type in
                    setOf("model","creative","archive") || verticalOriginal
                "MODS"->type in setOf("mod","archive","apk") || verticalOriginal
                "VOICE"->type in setOf("audio","archive") || verticalOriginal
                "CREATORS"->type=="video"||creatorOriginal
                "COSPLAY","ARTWORK"->galleryOriginal
                "VIDEOS","ADULT","HENTAI"->type=="video"
                else->false
            }
            if(!allowed || results.size>=maxOriginals)return
            val name=filenameHint.ifBlank{caption}.ifBlank{source.title}.take(180)
            results.putIfAbsent(url,IndexedItem(url,name,source.preview,
                source.source,source.area,
                if(creatorOriginal || galleryOriginal ||
                    (verticalOriginal && type=="image"))"original" else type,
                clock,false))
        }
        for(link in content.take(10_000)){
            val explicit=link.hasAttr("download") ||
                link.hasClass("download") ||
                link.closest(".attachments,.attachment,.downloads,.download")!=null
            val nameHint=link.attr("download").ifBlank{link.attr("data-filename")}
            record(link.absUrl("href").substringBefore('#'),
                nameHint.ifBlank{link.attr("title")}.ifBlank{link.text()},
                explicit,nameHint)
            if(results.size>=maxOriginals)break
        }
        // Publicly exposed file URLs on a post may be stored in button
        // attributes rather than href. A page preview alone is not an original.
        for(node in doc.select("main [data-download-url],main [data-file-url],"+
                "article [data-download-url],article [data-file-url],"+
                ".post [data-download-url],.post [data-file-url],"+
                ".attachments [data-download-url],.attachments [data-file-url],"+
                ".download [data-download-url],.download [data-file-url]").take(10_000)){
            for(attr in listOf("data-download-url","data-file-url")){
                val raw=node.attr(attr)
                if(raw.isBlank())continue
                val resolved=runCatching{URI(doc.location()).resolve(raw).toString()}
                    .getOrDefault("")
                val hint=node.attr("data-filename").ifBlank{node.attr("download")}
                record(resolved.substringBefore('#'),
                    hint.ifBlank{node.attr("title")}.ifBlank{node.text()},
                    true,hint)
            }
            if(results.size>=maxOriginals)break
        }
        // Gallery detail pages often store their full-resolution image links in
        // explicit data attributes. Never treat the ordinary img[src] thumbnail
        // or social meta preview as an original to acquire.
        if(source.area in setOf("COSPLAY","ARTWORK","CREATORS",
            "STUDIO","MMD","LIVE2D","ANIMATION")){
            val selectors="main img[data-original],main img[data-full],"+
                "main img[data-full-src],main img[data-highres],"+
                "article img[data-original],article img[data-full],"+
                "article img[data-full-src],article img[data-highres],"+
                ".gallery img[data-original],.gallery img[data-full],"+
                ".gallery img[data-full-src],.gallery img[data-highres],"+
                ".post img[data-original],.post img[data-full]"
            for(image in doc.select(selectors).take(10_000)){
                for(attr in listOf("data-original","data-full","data-full-src","data-highres")){
                    val raw=image.attr(attr)
                    if(raw.isBlank())continue
                    val url=runCatching{URI(doc.location()).resolve(raw).toString()}
                        .getOrDefault("")
                    record(url.substringBefore('#'),image.attr("alt"),true)
                }
                if(results.size>=maxOriginals)break
            }
        }
        if(source.area in setOf("VIDEOS","ADULT","HENTAI","CREATORS")){
            for(video in doc.select("main video[src],main video source[src],"+
                "article video[src],article video source[src],"+
                ".post video[src],.post video source[src]")){
                val url=video.absUrl("src").substringBefore('#')
                if(!safe(url)||kind(url,source.area)!="video")continue
                results.putIfAbsent(url,IndexedItem(url,source.title,source.preview,
                    source.source,source.area,"video",clock,false))
            }
        }
        require(results.size<maxOriginals){
            "Album reached the generic original-file limit; use a dedicated gallery adapter"
        }
        return results.values.toList()
    }

    /** Pagination is recognized only inside navigation controls. Numeric post IDs are
     * never treated as pages, even when their anchor text happens to be a number.
     */
    private fun pageNumber(url:String):Int? {
        val uri=runCatching{URI(url)}.getOrNull()?:return null
        for(pair in uri.rawQuery.orEmpty().split('&')){
            val key=pair.substringBefore('=').lowercase(Locale.ROOT)
            if(key in setOf("page","paged","pagenum","page_num")){
                val value=pair.substringAfter('=',"").toIntOrNull()
                if(value!=null && value>0)return value
            }
        }
        val path=uri.path.orEmpty()
        return Regex("(?i)(?:^|/)(?:page|paged)/([0-9]+)(?:/|$)")
            .find(path)?.groupValues?.get(1)?.toIntOrNull()
    }
    internal fun next(doc:Document,base:String,current:String=doc.location()):String? {
        SiteBackfillProfiles.next(doc,base,current)?.let{return it}
        val explicit=doc.select("a[rel=next][href],link[rel=next][href],"+
            "a.next[href],.pagination a.next[href],"+
            "a[aria-label=Next][href],a[aria-label='Next page'][href]")
        for(anchor in explicit){
            val link=anchor.absUrl("href").substringBefore('#')
            if(safe(link)&&sameSite(link,base)&&link!=current)return link
        }
        val expected=(pageNumber(current)?:1)+1
        val controls=doc.select(".pagination a[href],.pager a[href],"+
            "nav[aria-label*=Pagination] a[href],a.page-numbers[href]")
        for(anchor in controls){
            val link=anchor.absUrl("href").substringBefore('#')
            if(safe(link) && sameSite(link,base) && link!=current &&
                pageNumber(link)==expected)return link
        }
        return null
    }
}

object SourceIndex {
    private const val RECURRING_ID=48531
    private const val CATCHUP_ID=48532
    private const val STATE="asterion_source_index_cursor"
    private val busy=AtomicBoolean(false)
    private val previewBusy=AtomicBoolean(false)
    private val previewDbLock=Any()
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
            db.migrateOfficialSource(
                "https://www.omg.xxx/fr/","https://www.omg.porn/fr/")
            db.migrateOfficialSource(
                "https://pornhub.com/","https://www.pornhub.com/")
            db.migrateOfficialSource(
                "https://roshy.tv/category/decensored/",
                "https://roshy.tv/category/decensored-6/")
            db.migrateOfficialSource(
                "https://m.tnaflix.com/","https://www.tnaflix.com/")
            db.migrateOfficialSource(
                "https://freewebnovel.com/","https://freewebnovel.com/home")
            db.migrateOfficialSource(
                "https://novelbuddy.com/home","https://novelbuddy.me/home")
            for(site in AlohaSources.all){
                val ownsFeed=site.host in setOf("onejav.com","sukebei.nyaa.si")
                db.add(site.url,site.name,site.area.name,
                    if(ownsFeed ||
                        site.host in AlohaSources.alreadyIntegratedHosts)
                        "native" else "generic")
                if(ownsFeed)db.setAdapter(site.url,"native")
            }
            for((name,url,area) in extra)db.add(url,name,area,
                if(name in setOf("KoikatsuCards","Pawchive","Kemono","Coomer"))"native" else "generic")
            val added=context.getSharedPreferences("asterion_custom_site_enrollment",0)
                .getStringSet("sites",emptySet()).orEmpty()
            for(record in added){
                val delim=record.indexOf('|')
                if(delim<1)continue
                val type=record.substring(0,delim)
                val url=record.substring(delim+1)
                if(!SourceIndexParser.safe(url) ||
                    ProviderRetirement.isRetired(url))continue
                val area=when(type){
                    "PIXIV"->"ARTWORK"
                    "X"->"X"
                    "COSPLAY"->"COSPLAY"
                    "VIDEO"->if(AlohaSources.all.any{
                        SiteControls.key(it.url)==SiteControls.key(url) &&
                            it.area==AlohaArea.HENTAI
                    })"HENTAI" else "ADULT"
                    "BOOKS"->"BOOKS"
                    "COMICS"->"COMICS"
                    "TORRENTS"->"GAMES"
                    "ASSETS"->"ASSETS"
                    else->"CREATORS"
                }
                db.add(url,URI(url).host.orEmpty(),area,
                    if(type in setOf("PIXIV","X","COSPLAY","VIDEO") ||
                        (type=="BOOKS" && NovelEpubParser.supportedSeries(url)))
                        "native" else "generic")
            }
        }
        // Suppress retired hosts after all legacy migrations and custom-site
        // enrollment have finished. Never resurrect an old OMG/Pornhits watch.
        ProviderRetirement.disableExisting(context)
        val jobs=context.getSystemService(JobScheduler::class.java)
        if(jobs.allPendingJobs.none{it.id==RECURRING_ID})
            jobs.schedule(JobInfo.Builder(RECURRING_ID,
                ComponentName(context,SourceIndexJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true).setPeriodic(6L*60L*60L*1000L).build())
        if(needsCatchup(context))scheduleCatchup(context)
        else nextRetryMs(context)?.let{scheduleCatchup(context,delayMs=it)}
    }
    fun nextRetryMs(context:Context):Long? = SourceIndexDb(context).use{db->
        if(db.sources().none{
                it.adapter=="generic" &&
                    SiteControls.explicitlyEnabled(context,it.url)
            })null else db.nextDetailRetryMs()
    }
    /** A complete provider round is not the same as its historical backfill ending. */
    fun needsCatchup(context:Context):Boolean {
        // Discovered pages and old outstanding non-user transfers are not
        // permission to reschedule an infinite catch-up loop.
        return SourceIndexDb(context).use{db->
            val selected=db.sources().filter{it.enabled &&
                it.adapter=="generic" &&
                    SiteControls.explicitlyEnabled(context,it.url)}
            selected.any{!it.cursor.isNullOrBlank() &&
                it.error.isNullOrBlank()} ||
                (selected.isNotEmpty() &&
                    db.pendingDetails(50).any{item->
                        SiteControls.explicitlyEnabled(context,item.source)
                    }) ||
                db.pendingDirect(100).any{originalRequested(context,it)}
        }
    }
    fun scheduleCatchup(context:Context,force:Boolean=false,delayMs:Long=30_000L){
        val jobs=context.getSystemService(JobScheduler::class.java)
        if(!force && jobs.allPendingJobs.any{it.id==CATCHUP_ID})return
        val delay=delayMs.coerceIn(1_000L,6L*60*60_000L)
        jobs.schedule(JobInfo.Builder(CATCHUP_ID,ComponentName(context,SourceIndexJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setMinimumLatency(delay).setOverrideDeadline(delay+4L*60_000L).build())
    }
    /**
     * Examine every registered provider in this module, not just four per
     * 15-minute interval. These listing checks never enqueue originals or
     * advance a backfill cursor. Four bounded workers isolate slow sites.
     */
    fun refreshPreviews(context:Context,area:String,finished:(Int)->Unit={}) {
        if(area !in setOf("ADULT","HENTAI","COSPLAY")) { finished(0); return }
        thread(name="asterion-all-site-previews-"+area.lowercase(Locale.ROOT)){
            if(!AppPower.enabled(context) || !previewBusy.compareAndSet(false,true)){
                finished(0)
                return@thread
            }
            val executor=java.util.concurrent.Executors.newFixedThreadPool(4)
            var newPreviews=0
            try {
                ensure(context)
                val sites=SourceIndexDb(context).use{db->
                    db.sources().filter{
                        it.area==area && SourceIndexParser.safe(it.url)
                    }.distinctBy{SiteControls.key(it.url)}
                }
                val before=SourceIndexDb(context).use{db->
                    db.items(area,4000).count{!it.preview.isNullOrBlank()}
                }
                val jobs=sites.map{site->
                    executor.submit(java.util.concurrent.Callable {
                        runCatching{scanOne(context,site,previewOnly=true)}
                            .onFailure{error->
                                SourceIndexDb(context).use{
                                    it.failure(site.url,error.message?:"Preview check failed")
                                }
                            }
                    })
                }
                for(job in jobs){
                    if(!AppPower.enabled(context))break
                    runCatching{job.get()}
                }
                newPreviews=(SourceIndexDb(context).use{db->
                    db.items(area,4000).count{!it.preview.isNullOrBlank()}
                }-before).coerceAtLeast(0)
            }finally{
                executor.shutdownNow()
                previewBusy.set(false)
                finished(newPreviews)
            }
        }
    }

    /** Perform a real listing check for one site, never the arbitrary next
     * four sites when the user taps that site's CHECK NEW button.
     */
    fun refreshSitePreview(context:Context,area:String,address:String,
                           finished:(Boolean)->Unit={}) {
        thread(name="asterion-single-site-preview"){
            if(!AppPower.enabled(context)){ finished(false); return@thread }
            ensure(context)
            val source=SourceIndexDb(context).use{db->
                db.sources().firstOrNull{
                    it.area==area && SiteControls.key(it.url)==SiteControls.key(address)
                }
            }
            val result=source!=null && runCatching{
                scanOne(context,source,previewOnly=true)
            }.onFailure{error->
                SourceIndexDb(context).use{
                    it.failure(source!!.url,error.message?:"Preview check failed")
                }
            }.isSuccess
            finished(result)
        }
    }

    /** The user may backfill one registered site without resetting unrelated
     * cursors or changing other providers' include/live preferences.
     */
    fun scanSourceNow(context:Context,address:String) {
        if(!AppPower.enabled(context))return
        thread(name="asterion-source-specific-backfill"){
            if(!busy.compareAndSet(false,true))return@thread
            try {
                val source=SourceIndexDb(context).use{db->
                    db.sources().firstOrNull{it.url==address}
                }?:return@thread
                if(!source.enabled || source.adapter!="generic")return@thread
                runCatching{scanOne(context,source)}.onFailure{error->
                    SourceIndexDb(context).use{db->
                        db.failure(address,error.message?:"Source-specific backfill failed")
                    }
                }
            }finally{
                busy.set(false)
                if(AppPower.enabled(context))scheduleCatchup(context,force=true)
            }
        }
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
                    db.failure(address,it.message?:"Source unavailable")
                }
            }
        }
    }
    fun scan(context:Context,area:String?=null,stop:()->Boolean={false}) {
        if(!AppPower.enabled(context)||!busy.compareAndSet(false,true))return
        BackfillPerformance.backgroundWorker()
        try {
            val pref=context.getSharedPreferences(STATE,0)
            val all=SourceIndexDb(context).use{it.sources().filter{source->
                source.enabled && SiteControls.any(context,source.url) &&
                    source.adapter=="generic" &&
                    (area==null||area==source.area)
            }}
            // Pending post details and original enqueue retries must keep
            // running even when no listing provider is currently active.
            // Each enabled website is an independent agent with a durable
            // cursor. No first-five/first-twenty provider truncation.
            val tasks=all.map{source->
                BackfillAgents.submit(context,"source:"+source.url){
                    if(!stop() && AppPower.enabled(context))
                        runCatching{scanOne(context,source)}.onFailure{
                            SourceIndexDb(context).use{db->
                                db.failure(source.url,
                                    it.message?:"Source unavailable")
                            }
                        }
                }
            }
            // Waiting happens on an existing background worker, never UI.
            for(task in tasks){
                if(stop() || !AppPower.enabled(context))break
                runCatching{task.get()}
            }
            // Persist every discovered original, but bound actual Android
            // DownloadManager submissions in one scheduler pass. The remaining
            // originals keep their durable unqueued state for catch-up.
            // Direct originals are now routed by two asynchronous agents PER
            // module. Discovery must not occupy those download lanes.
            // Visit each discovered detail page independently of listing
            // pagination; retries are persisted and bounded to protect the phone.
            for(item in SourceIndexDb(context).use{it.pendingDetails(50)}){
                if(stop() || !AppPower.enabled(context))break
                if(area!=null && item.area!=area)continue
                if(!SiteControls.explicitlyEnabled(context,item.source))continue
                runCatching{
                    val source=SourceIndexDb(context).use{db->
                        db.sources().firstOrNull{x->x.url==item.source}
                    }?:error("Original provider is no longer enrolled")
                    require(source.enabled &&
                        SourceIndexParser.sameSite(source.url,item.url)){
                        "Detail is outside its approved source"
                    }
                    val doc=fetchPage(context,source,item.url)
                    val checkedAt=System.currentTimeMillis()
                    val files=(SiteBackfillProfiles.galleryOriginals(doc,item,checkedAt)+
                        SourceIndexParser.detailOriginals(doc,item,checkedAt))
                        .distinctBy{it.url}
                    val videoPage=item.area in setOf("VIDEOS","ADULT","HENTAI","CREATORS") &&
                        com.asterion.videoflow.WebDiscovery.isVideoPage(item.url)
                    // Native VideoFlow resolves compatible HTML video pages,
                    // including embedded streams that have no direct MP4 anchor.
                    // A request accepted by VideoFlow is NOT counted as a
                    // confirmed complete original file.
                    val routed=if(files.isEmpty() && videoPage){
                        val added=VideoDb(context).use{it.addVideo(item.url,item.title)}
                        if(added)VideoEngine.launch(context,false)
                        added || VideoDb(context).use{it.hasVideo(item.url)}
                    }else false
                    require(files.isNotEmpty() || routed){
                        "No accessible original found in this post; provider-specific adapter needed"
                    }
                    SourceIndexDb(context).use{db->
                        for(file in files){
                            db.upsert(file)
                            // The module's two claimed agents enqueue this
                            // original after the source detail is persisted.
                        }
                        db.detailsFinished(item.url)
                    }
                }.onFailure{failure->
                    SourceIndexDb(context).use{db->db.detailsFailed(item.url,
                        failure.message?:"Post detail unavailable")}
                }
            }
            // Dispatch up to two asynchronous original queues per module,
            // with atomic per-file claims and persisted due-time retries.
            ModuleOriginalAgents.startAll(context,area)
            // Use the established six-hour source scheduler for followed
            // creator profile and creator-designated hub discovery as well.
            // This is separate from post/media download identities.
            if(area==null && !stop() && AppPower.enabled(context))
                CreatorLinkDiscovery.scan(context,stop)
        }finally{
            busy.set(false)
            if(AppPower.enabled(context)){
                if(needsCatchup(context))scheduleCatchup(context)
                else nextRetryMs(context)?.let{scheduleCatchup(context,delayMs=it)}
            }
        }
    }
    /** Follow only validated same-site redirects, re-evaluating the cookie vault for
     * each destination instead of forwarding a manually supplied Cookie off-site.
     */
    private fun fetchPage(context:Context,source:IndexedSource,address:String):Document {
        try{
            var current=address
            repeat(4){
                require(SourceIndexParser.safe(current) &&
                    SourceIndexParser.sameSite(source.url,current)){
                    "Source redirected outside its approved provider domain"
                }
                val response=Jsoup.connect(current).timeout(16000)
                    .maxBodySize(3_000_000)
                    .userAgent(HttpClient.USER_AGENT)
                    .header("Accept","text/html,application/xhtml+xml")
                    .followRedirects(false).ignoreHttpErrors(true)
                    .apply{SiteSession.cookie(current)?.let{header("Cookie",it)}}
                    .execute()
                if(response.statusCode() in 300..399){
                    val location=response.header("Location")
                        ?.takeIf{it.isNotBlank()}
                        ?:error("Redirect omitted its destination")
                    val next=URI(current).resolve(location).toString()
                    require(SourceIndexParser.safe(next) &&
                        SourceIndexParser.sameSite(source.url,next)){
                        "Provider redirected to an untrusted host"
                    }
                    current=next
                }else{
                    require(response.statusCode() in 200..299){
                        "Provider HTTP "+response.statusCode()+
                            "; account or source unavailable"
                    }
                    return response.parse()
                }
            }
            error("Provider exceeded safe redirect limit")
        }catch(error:Exception){
            // Some sites deliver provider-authorized content only in their
            // browser session. Use the existing safe WebView content parser
            // after the user has actually connected that account.
            if(!SiteSession.bound(context,address))throw error
            val browser=BrowserPageFetch.html(context,address)
            return Jsoup.parse(browser,address)
        }
    }
    /** A real synchronous single-site scan, owned by a cancellable site job. */
    fun scanSingle(context:Context,address:String,stop:()->Boolean){
        val source=SourceIndexDb(context).use{db->
            db.sources().firstOrNull{it.url==address}
        }?:error("Site is not registered")
        require(source.adapter=="generic"){"This provider uses a native scanner"}
        try{
            scanOne(context,source,stop,true)
            if(stop())return
            // Follow the source's actual detail links and queue originals,
            // rather than treating a count of HTML pages as a completed backfill.
            var enqueued=0
            val details=SourceIndexDb(context).use{
                it.pendingForSource(address,true,24)
            }
            for(item in details){
                if(stop() || !AppPower.enabled(context))return
                try{
                    val doc=fetchPage(context,source,item.url)
                    val clock=System.currentTimeMillis()
                    val originals=(SiteBackfillProfiles.galleryOriginals(doc,item,clock)+
                        SourceIndexParser.detailOriginals(doc,item,clock))
                        .distinctBy{it.url}
                        .filter{file->
                            IndexedMediaQuality.isMedia(
                                file.url,file.title,file.preview,file.area) ||
                            (file.kind in setOf("original","epub","archive",
                                "model","video","image","torrent") &&
                                SourceIndexParser.safe(file.url) &&
                                SourceIndexParser.autoOriginal(file))
                        }
                    if(originals.isEmpty() &&
                        item.area in setOf("ADULT","HENTAI","VIDEOS") &&
                        com.asterion.videoflow.WebDiscovery.isVideoPage(item.url)){
                        val added=VideoDb(context).use{
                            it.addVideo(item.url,item.title)
                        }
                        if(added)enqueued++
                    }
                    SourceIndexDb(context).use{db->
                        for(file in originals){
                            db.upsert(file)
                            if(SourceIndexParser.autoOriginal(file) &&
                                submitOriginal(context,file)){
                                db.markQueued(file.url)
                                enqueued++
                            }
                        }
                        if(originals.isNotEmpty())db.detailsFinished(item.url)
                        else db.detailsFailed(item.url,
                            "No accessible original; provider parser or sign-in required")
                    }
                }catch(t:Exception){
                    SourceIndexDb(context).use{it.detailsFailed(item.url,
                        t.message?:"Unable to inspect original")}
                }
            }
            // Direct-file originals do not need another detail fetch.
            for(item in SourceIndexDb(context).use{
                it.pendingForSource(address,false,40)
            }){
                if(stop() || !AppPower.enabled(context))return
                if(SourceIndexParser.autoOriginal(item) && submitOriginal(context,item)){
                    SourceIndexDb(context).use{it.markQueued(item.url)}
                    enqueued++
                }
            }
            if(enqueued>0)VideoEngine.launch(context,false)
        }catch(e:Exception){
            if(!stop())SourceIndexDb(context).use{it.failure(address,
                e.message?:"Site check failed")}
            throw e
        }
    }
    private fun scanOne(context:Context,source:IndexedSource,
                        stop:()->Boolean={false},manual:Boolean=false,
                        previewOnly:Boolean=false){
        // For providers with a verified, first-party JSON listing, parse the
        // published catalog directly. An authentic cover is not an original
        // download, and a preview-only refresh must not move history cursors.
        val specialist=source.url.let{
            val host=runCatching{URI(it).host.orEmpty().lowercase()
                .removePrefix("www.")}.getOrDefault("")
            host in setOf("eporner.com","hentaibros.net") &&
                source.area in setOf("ADULT","HENTAI")
        }
        if(specialist){
            var cursor=if(previewOnly) null else source.cursor
            var fresh=0
            var pageCount=0
            var more:String?
            do {
                if(stop() || Thread.currentThread().isInterrupted ||
                    !AppPower.enabled(context))return
                val result=VerifiedSiteCatalogApi.fetch(context,source,cursor)
                    ?:error("Provider catalog adapter unavailable")
                if(pageCount==0 && result.media.isEmpty()){
                    error("Provider published no supported catalog media")
                }
                synchronized(previewDbLock){
                    SourceIndexDb(context).use{db->
                        for(item in result.media){
                            if(db.upsert(item))fresh++
                        }
                    }
                }
                more=result.next
                pageCount++
                cursor=more
                if(!previewOnly)SourceIndexDb(context).use{db->
                    db.update(source.url,cursor,null,source.discovered+fresh,
                        if(manual && cursor==null)true else null)
                }
            }while(!previewOnly && manual && more!=null &&
                pageCount<8 && !stop())
            if(previewOnly)SourceIndexDb(context).use{db->
                db.update(source.url,source.cursor,null,source.discovered+fresh)
            }
            return
        }
        var cursor=source.cursor
        var fresh=0
        val pages=ArrayList<String>()
        pages+=source.url
        val saved=source.cursor?.takeIf{
            it!=source.url && SourceIndexParser.safe(it) &&
                SourceIndexParser.sameSite(source.url,it)
        }
        val historyEnabled=manual || SiteControls.sync(context,source.url,source.enabled)
        if(saved!=null && historyEnabled && !source.historyComplete)pages+=saved
        var index=0
        val visited=HashSet<String>()
        val pageBudget=if(previewOnly)1 else if(manual)8 else 5
        while(index<pages.size && index<pageBudget &&
            (historyEnabled && !source.historyComplete || index==0)){
            if(stop()||Thread.currentThread().isInterrupted) return
            if(!AppPower.enabled(context) ||
                (!manual && !previewOnly && !SiteControls.sync(context,source.url)))break
            val address=pages[index++]
            if(!visited.add(address))continue
            var doc=fetchPage(context,source,address)
            // A JS-only website may reply HTTP 200 with no actual content.
            // Re-read the authorized browser DOM before treating it as empty.
            if(SiteSession.bound(context,address) &&
                SourceIndexParser.entries(doc,source.url,source.area,
                    System.currentTimeMillis()).isEmpty()){
                val browser=runCatching{BrowserPageFetch.html(context,address)}
                    .getOrNull()
                if(!browser.isNullOrBlank())doc=Jsoup.parse(browser,address)
            }
            val landing=doc.location()
            require(SourceIndexParser.safe(landing) &&
                SourceIndexParser.sameSite(source.url,landing)){
                "Source redirected outside its approved provider"
            }
            // Only explicit social links in a real creator profile are candidates;
            // never infer same identity from unrelated names or site listing cards.
            if(source.area in setOf("CREATORS","ARTWORK","COSPLAY","ASSETS")){
                val links=CrossPlatformProfileParser.linkedProfiles(doc,source.url)
                if(links.isNotEmpty())CrossPlatformCreatorDb(context).use{
                    it.observe(links)
                }
            }
            val records=SourceIndexParser.entries(doc,source.url,source.area,System.currentTimeMillis())
            synchronized(previewDbLock){
                SourceIndexDb(context).use{db->
                    for(item in records){
                        if(db.upsert(item))fresh++
                    }
                }
            }
            // All original downloads remain owned by the existing queue.
            // Catalog discovery itself never enqueues originals.
            // Catalog-only checks update last-check/index count, but never
            // advance a historical backfill cursor or queue any downloads.
            if(previewOnly){
                SourceIndexDb(context).use{db->
                    db.update(source.url,source.cursor,null,
                        source.discovered+fresh,null)
                    if(records.isEmpty()){
                        db.failure(source.url,
                            "No supported public media on listing; login or adapter may be required")
                    }
                }
                return
            }
            val next=SourceIndexParser.next(doc,source.url,address)
            if(historyEnabled && index==1 && saved==null && next!=null && next !in visited)
                pages+=next
            else if(historyEnabled && index>1 && next!=null && next !in visited)
                pages+=next
            if(records.isEmpty() && fresh==0 && index==1 && saved==null)
                throw IllegalStateException("No supported originals found; dedicated adapter or login required")
            // Persist each successful page before the next request. A network failure
            // on the following page must resume here instead of restarting history.
            if(historyEnabled && !source.historyComplete)
                cursor=if(index==1 && saved!=null)saved else next
            val complete:Boolean?=if(historyEnabled)
                source.historyComplete || (next==null && (saved==null || index>1))
                else null
            SourceIndexDb(context).use{db->
                db.update(source.url,cursor,null,source.discovered+fresh,complete)
            }
        }
    }
    /** Discover and index all sources, but never enqueue unrequested
     * books, comics, games or media from an auto-seeded provider.
     */
    internal fun originalRequested(context:Context,item:IndexedItem):Boolean =
        DownloadConsentPolicy.automaticAllowed(item.area,
            SiteControls.explicitlyEnabled(context,item.source)) &&
        SourceIndexParser.autoOriginal(item)

    internal fun submitOriginal(context:Context,item:IndexedItem,
                                 onDirectEnqueued:(Long)->Unit={}):Boolean=runCatching{
        if(!SourceIndexParser.safe(item.url))return false
        if(item.kind=="video"){
            // A website indexing original is not an explicit manual selection.
            val queued=VideoDb(context).use{
                it.addVideo(item.url,item.title,origin="website")
            }
            if(queued)VideoEngine.launch(context,false)
            // Existing downloads, including strict full-length holds, are owned by
            // VideoFlow and must not be enqueued repeatedly by the source scanner.
            return queued || VideoDb(context).use{it.hasVideo(item.url)}
        }
        if(!SourceIndexParser.autoOriginal(item))return false
        if(item.area=="COMICS" && (NhentaiChapters.isGallery(item.url) ||
            SushiScanChapters.isSeries(item.url))){
            ChapterQueue(context).use{it.enqueue(item.url,item.title)}
            ChapterArchives.start(context)
            return true
        }
        if(item.area=="BOOKS" && item.kind=="books"){
            NovelEpubService.enqueue(context,item.url,item.title)
            return true
        }
        val suffix=when(item.kind){
            "epub"->".epub";"apk"->".apk";"archive"->".zip";else->".bin"
        }
        // Keep the explicit original filename for signed endpoints whose URL
        // is only an opaque file ID. Never use arbitrary post titles as paths.
        val suppliedName=item.title.takeIf{
            it.matches(Regex("""(?i)[^/\\]{1,150}\.(?:epub|zip|7z|rar|png|jpg|jpeg|gif|webp|avif|blend|fbx|obj|glb|gltf|stl|pmx|pmd|vmd|vpd|vrm|moc3|psd|kra|clip|unitypackage|pak|xp3|rpa|rpy|mp3|flac|m4a|wav|ogg|opus|mp4|webm|mkv|apk)"""))
        }
        val filename=(suppliedName?:URI(item.url).path.substringAfterLast('/'))
            .replace(Regex("[^A-Za-z0-9._-]"),"_").take(100)
            .ifBlank{"original_"+System.currentTimeMillis()+suffix}
        val folder=ModuleOriginalAgents.folder(item.area)
        if(!CrossSourceDedup(context).use{it.reserve(item.url)}){
            // Existing accepted transfers are already owned by DownloadManager.
            // An unattached lease is different: keep that file in retry state.
            return CrossSourceDedup(context).use{it.alreadyHandled(item.url)}
        }
        try{
            val manager=context.getSystemService(DownloadManager::class.java)
            val req=DownloadManager.Request(Uri.parse(item.url)).apply{
                setTitle(filename)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                    "AsterionDownloader/$folder/$filename")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                SiteSession.cookie(item.url)?.let{addRequestHeader("Cookie",it)}
                ResourceDownloads.privacyReferer(item.url,item.source)?.let{
                    addRequestHeader("Referer",it)
                }
            }
            val id=manager.enqueue(req)
            CrossSourceDedup(context).use{it.attach(item.url,id)}
            DirectDownloadTracker.record(context,id,filename,folder,item.url)
            DirectDownloadRetry.remember(context,id,item.url,folder,filename,item.source)
            if(id>0L)runCatching{onDirectEnqueued(id)}
            id>0L
        }catch(e:Exception){
            CrossSourceDedup(context).use{it.release(item.url)}
            throw e
        }
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
            finally{
                jobFinished(params,false)
                if(!stopped.get() && params.jobId==48532){
                    if(SourceIndex.needsCatchup(applicationContext))
                        SourceIndex.scheduleCatchup(applicationContext,force=true)
                    else SourceIndex.nextRetryMs(applicationContext)?.let{
                        SourceIndex.scheduleCatchup(applicationContext,
                            force=true,delayMs=it)
                    }
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
