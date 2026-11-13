package com.azrael.pixivdumpsync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.net.URI
import java.util.Locale

data class NovelEpubTask(val url:String,val title:String,val attempts:Int)
data class NovelArchiveStatus(val url:String,val title:String,val state:String,
    val attempts:Int,val error:String?,val savedUri:String?,val updated:Long,
    val syncEnabled:Boolean=true,val lastCheck:Long=0L,
    val retryAfter:Long=0L)

/** Separate EPUB collection queue; no PDF/CBZ and no changes to manga ZIP jobs. */
class NovelEpubQueue(context:Context):
    SQLiteOpenHelper(context,"asterion_novel_epub.sqlite",null,2) {
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("""CREATE TABLE novels(
            url TEXT PRIMARY KEY,title TEXT NOT NULL,
            state TEXT NOT NULL DEFAULT 'PENDING',
            attempts INTEGER NOT NULL DEFAULT 0,retry_after INTEGER NOT NULL DEFAULT 0,
            last_update INTEGER NOT NULL DEFAULT 0,last_error TEXT,
            saved_uri TEXT,
            sync_enabled INTEGER NOT NULL DEFAULT 1,
            last_check INTEGER NOT NULL DEFAULT 0,
            fingerprint TEXT)""")
        db.execSQL("CREATE INDEX novel_epub_queue ON novels(state,retry_after)")
    }
    override fun onUpgrade(db:SQLiteDatabase,old:Int,next:Int){
        if(old<2){
            db.execSQL("ALTER TABLE novels ADD COLUMN sync_enabled INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE novels ADD COLUMN last_check INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE novels ADD COLUMN fingerprint TEXT")
        }
    }
    @Synchronized fun enqueue(url:String,title:String):Boolean{
        require(NovelEpubParser.supportedSeries(url)){
            "Supported chapter-based novel series URL required"
        }
        val v=ContentValues().apply{
            put("url",url);put("title",title.take(180))
            put("state","PENDING");put("last_update",System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict("novels",null,v,
            SQLiteDatabase.CONFLICT_IGNORE)>0
    }
    @Synchronized fun recover(){
        writableDatabase.execSQL("UPDATE novels SET state='PENDING' WHERE state='WORKING'")
    }
    /** Atomically reserve one complete-book task. Two EPUB workers must
     * never generate the same series or overwrite each other's saved receipt.
     */
    fun claimNext():NovelEpubTask?{
        val db=writableDatabase
        db.beginTransactionNonExclusive()
        try{
            val now=System.currentTimeMillis()
            val task=db.rawQuery(
                "SELECT url,title,attempts FROM novels "+
                    "WHERE state IN ('PENDING','RETRY') AND retry_after<=? "+
                    "ORDER BY last_update ASC LIMIT 1",
                arrayOf(now.toString())).use{c->
                if(c.moveToFirst())NovelEpubTask(
                    c.getString(0),c.getString(1),c.getInt(2)) else null
            }
            if(task==null){db.setTransactionSuccessful();return null}
            val fields=ContentValues().apply{
                put("state","WORKING");put("last_update",now)
            }
            val changed=db.update("novels",fields,
                "url=? AND state IN ('PENDING','RETRY') AND retry_after<=?",
                arrayOf(task.url,now.toString()))
            db.setTransactionSuccessful()
            return if(changed==1)task else null
        }finally{db.endTransaction()}
    }
    fun next():NovelEpubTask?{
        val now=System.currentTimeMillis().toString()
        return readableDatabase.rawQuery(
            """SELECT url,title,attempts FROM novels
                WHERE state IN ('PENDING','RETRY') AND retry_after<=?
                ORDER BY last_update ASC LIMIT 1""",arrayOf(now)).use{c->
            if(c.moveToFirst())NovelEpubTask(c.getString(0),c.getString(1),c.getInt(2))
            else null
        }
    }
    data class Snapshot(val pending:Int,val active:Int,val complete:Int,
                        val lastError:String?)
    fun snapshot():Snapshot{
        val counts=HashMap<String,Int>()
        readableDatabase.rawQuery("SELECT state,COUNT(*) FROM novels GROUP BY state",
            null).use{c->
            while(c.moveToNext())counts[c.getString(0)]=c.getInt(1)
        }
        val error=readableDatabase.rawQuery(
            "SELECT last_error FROM novels WHERE last_error IS NOT NULL "+
                "ORDER BY last_update DESC LIMIT 1",null).use{c->
            if(c.moveToFirst())c.getString(0)else null
        }
        return Snapshot(counts.getOrDefault("PENDING",0)+
            counts.getOrDefault("RETRY",0),counts.getOrDefault("WORKING",0),
            counts.getOrDefault("DONE",0),error)
    }
    fun list(limit:Int=50):List<NovelArchiveStatus>{
        val results=ArrayList<NovelArchiveStatus>()
        readableDatabase.rawQuery(
            """SELECT url,title,state,attempts,last_error,saved_uri,last_update,
                      sync_enabled,last_check,retry_after
               FROM novels ORDER BY last_update DESC
               LIMIT ${limit.coerceIn(1,300)}""",null).use{cursor->
            while(cursor.moveToNext()){
                results+=NovelArchiveStatus(
                    cursor.getString(0),cursor.getString(1),cursor.getString(2),
                    cursor.getInt(3),cursor.getString(4),cursor.getString(5),
                    cursor.getLong(6),cursor.getInt(7)==1,
                    cursor.getLong(8),cursor.getLong(9))
            }
        }
        return results
    }
    /** Progress is neither a failed transfer nor a completed book. Reclaim
     * the same series after a bounded JobService time slice, preserving all
     * downloaded chapter caches and its precise saved reader cursor.
     */
    fun continueLater(url:String,cached:Int){
        val now=System.currentTimeMillis()
        writableDatabase.update("novels",ContentValues().apply{
            put("state","RETRY");put("retry_after",now+20_000L)
            // A scheduled continuation is not a failed book.
            // The live cached count is computed from chapter files.
            putNull("last_error")
            put("last_update",now)
        },"url=? AND state='WORKING'",arrayOf(url))
    }

    /** Schedule future partial books as well as already-due books. */
    fun nextDueDelayMs(now:Long=System.currentTimeMillis()):Long? {
        if(hasDue())return 1_000L
        val next=readableDatabase.rawQuery(
            "SELECT MIN(retry_after) FROM novels "+
                "WHERE state='RETRY' AND retry_after>?",
            arrayOf(now.toString())
        ).use { c->
            if(c.moveToFirst()&&!c.isNull(0))c.getLong(0) else null
        }
        return next?.let{(it-now).coerceAtLeast(1_000L)}
    }

    fun hasDue():Boolean=readableDatabase.rawQuery(
        "SELECT 1 FROM novels WHERE state IN ('PENDING','RETRY') AND retry_after<=? LIMIT 1",
        arrayOf(System.currentTimeMillis().toString())).use{it.moveToFirst()}
    /** Live-sync completed books without touching account-blocked or paused jobs.
     * Historical chapters remain cached; a repeat scan does not publish again
     * if the provider's observed chapter catalogue has not changed.
     */
    @Synchronized fun refreshDue(now:Long=System.currentTimeMillis()):Int =
        writableDatabase.update("novels",ContentValues().apply{
            put("state","PENDING");put("retry_after",0L)
        },"state='DONE' AND sync_enabled=1 AND last_check<=?",
            arrayOf((now-6L*60*60_000L).toString()))

    fun setSync(url:String,enabled:Boolean){
        writableDatabase.update("novels",ContentValues().apply{
            put("sync_enabled",if(enabled)1 else 0)
        },"url=?",arrayOf(url))
    }
    /** Manual retry must clear backoff without deleting cached chapters. */
    @Synchronized fun retryNow(url:String):Boolean=
        writableDatabase.update("novels",ContentValues().apply{
            put("state","PENDING");put("retry_after",0L)
            put("last_update",System.currentTimeMillis())
            putNull("last_error")
        },"url=? AND state IN ('RETRY','ERROR','DONE')",arrayOf(url))>0

    fun requestedRefresh(url:String):Boolean =
        writableDatabase.update("novels",ContentValues().apply{
            put("state","PENDING");put("retry_after",0L)
            put("last_update",System.currentTimeMillis())
            putNull("last_error")
        },"url=? AND state IN ('DONE','RETRY','ERROR')",arrayOf(url))>0

    fun previous(url:String):Pair<String,String>?=readableDatabase.rawQuery(
        "SELECT saved_uri,fingerprint FROM novels WHERE url=? AND state='WORKING'",
        arrayOf(url)).use{c->
        if(c.moveToFirst() && !c.isNull(0) && !c.isNull(1))
            c.getString(0) to c.getString(1) else null
    }
    fun started(url:String){
        writableDatabase.update("novels",ContentValues().apply{
            put("state","WORKING");put("last_update",System.currentTimeMillis())
        },"url=?",arrayOf(url))
    }
    fun completed(url:String,uri:String,fingerprint:String){
        writableDatabase.update("novels",ContentValues().apply{
            put("state","DONE");put("saved_uri",uri)
            put("fingerprint",fingerprint);put("last_check",System.currentTimeMillis())
            put("retry_after",0);putNull("last_error")
            put("last_update",System.currentTimeMillis())
        },"url=?",arrayOf(url))
    }
    /** A blocked website must not be hammered automatically for hours.
     * Preserve acquired chapter cache until the website account is reconnected.
     */
    fun failure(item:NovelEpubTask,error:String,notBeforeEpochMs:Long=0L){
        val n=(item.attempts+1).coerceAtMost(30)
        val blocked=NovelAccountPolicy.needsBrowserConnection(error)
        val delay=(60_000L shl n.coerceAtMost(7)).coerceAtMost(6*60*60_000L)
        writableDatabase.update("novels",ContentValues().apply{
            put("state",if(blocked)"WAIT_LOGIN" else "RETRY")
            put("attempts",n)
            put("retry_after",if(blocked)0L else maxOf(
                System.currentTimeMillis()+delay,notBeforeEpochMs))
            put("last_error",error.take(200))
            put("last_update",System.currentTimeMillis())
        },"url=?",arrayOf(item.url))
    }

    /** Resume only the blocked novels belonging to the newly connected host. */
    @Synchronized fun resumeForSite(site:String):Int {
        val host=runCatching{URI(site).host?.lowercase(Locale.ROOT)
            ?.removePrefix("www.")}.getOrNull()?:return 0
        val waiting=ArrayList<String>()
        readableDatabase.rawQuery("SELECT url FROM novels WHERE state='WAIT_LOGIN'",
            null).use{cursor->
            while(cursor.moveToNext()){
                val url=cursor.getString(0)
                val source=runCatching{URI(url).host?.lowercase(Locale.ROOT)
                    ?.removePrefix("www.")}.getOrNull()
                if(source==host)waiting+=url
            }
        }
        for(url in waiting){
            writableDatabase.update("novels",ContentValues().apply{
                put("state","PENDING");put("retry_after",0L)
                put("last_update",System.currentTimeMillis())
            },"url=? AND state='WAIT_LOGIN'",arrayOf(url))
        }
        return waiting.size
    }

}
