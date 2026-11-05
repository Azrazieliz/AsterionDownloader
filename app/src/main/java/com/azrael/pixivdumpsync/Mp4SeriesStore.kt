package com.azrael.pixivdumpsync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Per-work persistent subscriptions. Parents and spin-offs share a family ID,
 * but keep their own volume floor, content type, scan cursor and dedup state.
 * No filename conventions are baked into this database.
 */
data class Mp4Follow(val id:Long,val family:String,val name:String,val aliases:String,
    val type:String,val firstVolume:Int,val backfill:Boolean,val sync:Boolean,
    val cursor:String?,val lastScan:Long,val error:String?)
data class Mp4Candidate(val url:String,val workId:Long,val title:String,
    val volume:Int?,val type:String,val state:String)

class Mp4SeriesStore(context:Context):
    SQLiteOpenHelper(context,"asterion_mp4_subscriptions.sqlite",null,1) {
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("""CREATE TABLE subscriptions(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            family TEXT NOT NULL,name TEXT NOT NULL,aliases TEXT NOT NULL DEFAULT '',
            type TEXT NOT NULL DEFAULT 'EPUB',
            first_volume INTEGER NOT NULL DEFAULT 1,
            backfill INTEGER NOT NULL DEFAULT 1,sync INTEGER NOT NULL DEFAULT 1,
            cursor TEXT,last_scan INTEGER NOT NULL DEFAULT 0,error TEXT,
            UNIQUE(family,name,type))""")
        db.execSQL("""CREATE TABLE candidates(
            url TEXT PRIMARY KEY,work_id INTEGER NOT NULL,title TEXT NOT NULL,
            volume INTEGER,type TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'DISCOVERED',
            updated INTEGER NOT NULL,download_id INTEGER)""")
        db.execSQL("CREATE INDEX candidate_work_state ON candidates(work_id,state)")
    }
    override fun onUpgrade(db:SQLiteDatabase,old:Int,next:Int){}
    @Synchronized fun add(family:String,name:String,firstVolume:Int=1,
                          type:String="EPUB",aliases:String=""):Long {
        require(name.isNotBlank() && name.length<=180 && family.length in 1..180)
        require(firstVolume in 1..999 && type in setOf("EPUB","ZIP"))
        val v=ContentValues().apply{
            put("family",family.trim());put("name",name.trim())
            put("aliases",aliases.take(600));put("type",type)
            put("first_volume",firstVolume)
        }
        writableDatabase.insertWithOnConflict("subscriptions",null,v,
            SQLiteDatabase.CONFLICT_IGNORE)
        return readableDatabase.rawQuery(
            "SELECT id FROM subscriptions WHERE family=? AND name=? AND type=?",
            arrayOf(family.trim(),name.trim(),type)).use{c->
            check(c.moveToFirst());c.getLong(0)
        }
    }
    fun list():List<Mp4Follow>{
        val out=ArrayList<Mp4Follow>()
        readableDatabase.rawQuery("""SELECT id,family,name,aliases,type,
            first_volume,backfill,sync,cursor,last_scan,error FROM subscriptions
            ORDER BY family COLLATE NOCASE,name COLLATE NOCASE""",null).use{c->
            while(c.moveToNext())out+=Mp4Follow(c.getLong(0),c.getString(1),
                c.getString(2),c.getString(3),c.getString(4),c.getInt(5),
                c.getInt(6)==1,c.getInt(7)==1,c.getString(8),
                c.getLong(9),c.getString(10))
        }
        return out
    }
    fun toggle(id:Long,backfill:Boolean,sync:Boolean){
        writableDatabase.update("subscriptions",ContentValues().apply{
            put("backfill",if(backfill)1 else 0)
            put("sync",if(sync)1 else 0)
        },"id=?",arrayOf(id.toString()))
    }
    fun startAt(id:Long,firstVolume:Int){
        require(firstVolume in 1..999)
        writableDatabase.update("subscriptions",ContentValues().apply{
            put("first_volume",firstVolume)
        },"id=?",arrayOf(id.toString()))
    }
    fun checked(id:Long,cursor:String?,error:String?){
        writableDatabase.update("subscriptions",ContentValues().apply{
            put("last_scan",System.currentTimeMillis())
            if(cursor==null)putNull("cursor") else put("cursor",cursor)
            if(error==null)putNull("error") else put("error",error.take(240))
        },"id=?",arrayOf(id.toString()))
    }
    fun observe(work:Mp4Follow,link:Mp4SeriesLink){
        val kind=if(link.isEpub)"EPUB" else "THREAD"
        val status=if(link.isEpub && Mp4SeriesRules.eligible(
                link.volume,work.firstVolume) && work.type=="EPUB")
            "READY" else "REVIEW"
        val v=ContentValues().apply{
            put("url",link.url);put("work_id",work.id)
            put("title",link.title);link.volume?.let{put("volume",it)}
            put("type",kind);put("state",status)
            put("updated",System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("candidates",null,v,
            SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun items(workId:Long,limit:Int=50):List<Mp4Candidate>{
        val out=ArrayList<Mp4Candidate>()
        readableDatabase.rawQuery("""SELECT url,work_id,title,volume,type,state
            FROM candidates WHERE work_id=? ORDER BY updated DESC LIMIT ?""",
            arrayOf(workId.toString(),limit.coerceIn(1,300).toString())).use{c->
            while(c.moveToNext())out+=Mp4Candidate(c.getString(0),c.getLong(1),
                c.getString(2),if(c.isNull(3))null else c.getInt(3),
                c.getString(4),c.getString(5))
        }
        return out
    }
    fun mark(url:String,state:String,downloadId:Long?=null){
        require(state in setOf("READY","REVIEW","QUEUED","PRESENT","FAILED"))
        writableDatabase.update("candidates",ContentValues().apply{
            put("state",state);put("updated",System.currentTimeMillis())
            downloadId?.let{put("download_id",it)}
        },"url=?",arrayOf(url))
    }
}
