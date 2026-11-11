package com.azrael.pixivdumpsync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ChapterTask(val id:Long,val url:String,val title:String,val state:String,
    val progress:Int,val error:String?,val savedUri:String?,val attempts:Int,val updated:Long=0L,
    val syncEnabled:Boolean=true,val lastCheck:Long=0L,val fingerprint:String?=null)

class ChapterQueue(context:Context):SQLiteOpenHelper(context,"asterion_chapter_queue.sqlite",null,3){
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("""CREATE TABLE tasks(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          url TEXT NOT NULL UNIQUE,
          title TEXT NOT NULL,
          state TEXT NOT NULL DEFAULT 'queued',
          progress INTEGER NOT NULL DEFAULT 0,
          attempts INTEGER NOT NULL DEFAULT 0,
          error TEXT,
          saved_uri TEXT,
          updated INTEGER NOT NULL,
          sync_enabled INTEGER NOT NULL DEFAULT 1,
          last_check INTEGER NOT NULL DEFAULT 0,
          fingerprint TEXT,
          requested_by TEXT NOT NULL DEFAULT 'user')""")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){
        if(oldVersion<2){
            db.execSQL("ALTER TABLE tasks ADD COLUMN sync_enabled INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE tasks ADD COLUMN last_check INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE tasks ADD COLUMN fingerprint TEXT")
        }
        if(oldVersion<3){
            db.execSQL("ALTER TABLE tasks ADD COLUMN requested_by TEXT NOT NULL DEFAULT 'legacy'")
            // Existing queue stays recoverable, but cannot silently restart
            // unsolicited archive jobs on a version upgrade.
            db.execSQL("""UPDATE tasks SET state='paused',
                error='Older queue held; RETRY to authorize'
                WHERE requested_by='legacy' AND state IN ('queued','working','error')""")
        }
    }
    fun enqueue(url:String,title:String=url):Boolean {
        val v=ContentValues().apply{
            put("url",url.trim());put("title",title.take(130));put("state","queued")
            put("requested_by","user")
            put("updated",System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict("tasks",null,v,
            SQLiteDatabase.CONFLICT_IGNORE)>0L
    }
    fun list():List<ChapterTask>{
        val result=ArrayList<ChapterTask>()
        readableDatabase.rawQuery("""SELECT id,url,title,state,progress,error,saved_uri,attempts,updated,
                   sync_enabled,last_check,fingerprint
            FROM tasks ORDER BY updated DESC LIMIT 1000""",null).use{c->
            while(c.moveToNext()){
                result.add(ChapterTask(c.getLong(0),c.getString(1),c.getString(2),
                    c.getString(3),c.getInt(4),c.getString(5),c.getString(6),
                    c.getInt(7),c.getLong(8),c.getInt(9)==1,c.getLong(10),
                    c.getString(11)))
            }
        }
        return result
    }
    fun setSync(id:Long,enabled:Boolean){
        writableDatabase.update("tasks",ContentValues().apply{
            put("sync_enabled",if(enabled)1 else 0)
        },"id=?",arrayOf(id.toString()))
    }
    fun refreshDue(now:Long=System.currentTimeMillis()):Int{
        val eligible=readableDatabase.rawQuery(
            "SELECT id,url FROM tasks WHERE state='done' AND sync_enabled=1 "+
            "AND last_check<=?",
            arrayOf((now-6L*60L*60_000L).toString())).use{c->
            val ids=ArrayList<Long>()
            while(c.moveToNext()){
                val url=c.getString(1)
                if(!NhentaiChapters.isGallery(url) && 
                    !ChapterScraper.isSingleChapter(url))ids+=c.getLong(0)
            }
            ids
        }
        for(id in eligible){
            writableDatabase.update("tasks",ContentValues().apply{
                put("state","queued");put("progress",0)
            },"id=? AND state='done'",arrayOf(id.toString()))
        }
        return eligible.size
    }
    fun savedSignature(id:Long):Pair<String,String>?=
        readableDatabase.rawQuery(
            "SELECT saved_uri,fingerprint FROM tasks WHERE id=?",
            arrayOf(id.toString())).use{c->
            if(c.moveToFirst() && !c.isNull(0) && !c.isNull(1))
                c.getString(0) to c.getString(1) else null
        }
    fun completeCheck(id:Long,saved:String,signature:String){
        writableDatabase.update("tasks",ContentValues().apply{
            put("state","done");put("progress",100)
            put("saved_uri",saved);put("fingerprint",signature)
            put("last_check",System.currentTimeMillis())
            put("updated",System.currentTimeMillis())
            putNull("error")
        },"id=?",arrayOf(id.toString()))
    }
    fun recover(){
        writableDatabase.execSQL("UPDATE tasks SET state='queued' WHERE state='working'")
    }
    /** SQL queue lookup has no UI-list limit: even large libraries retain
     * every pending series instead of starving tasks outside the first 1000.
     */
    fun next():ChapterTask?=readableDatabase.rawQuery(
        """SELECT id,url,title,state,progress,error,saved_uri,attempts,updated,
                  sync_enabled,last_check,fingerprint FROM tasks
           WHERE state='queued' ORDER BY updated ASC LIMIT 1""",null).use{c->
        if(c.moveToFirst())ChapterTask(c.getLong(0),c.getString(1),c.getString(2),
            c.getString(3),c.getInt(4),c.getString(5),c.getString(6),
            c.getInt(7),c.getLong(8),c.getInt(9)==1,c.getLong(10),c.getString(11))
        else null
    }
    fun update(task:Long,state:String,progress:Int,error:String?=null,savedUri:String?=null){
        val v=ContentValues().apply{
            put("state",state);put("progress",progress.coerceIn(0,100))
            put("updated",System.currentTimeMillis())
            if(error==null)putNull("error") else put("error",error.take(600))
            if(savedUri!=null)put("saved_uri",savedUri)
        }
        writableDatabase.update("tasks",v,"id=?",arrayOf("$task"))
    }
    fun attempt(task:Long){
        writableDatabase.execSQL("UPDATE tasks SET attempts=attempts+1 WHERE id=$task")
    }
    /** Manual retry resets exhausted budget; automatic retries must not. */
    fun pause(task:Long){
        update(task,"paused",0,error="Paused by user; cached pages retained")
    }
    fun retry(task:Long,manual:Boolean=true){
        update(task,"queued",0)
        if(manual)writableDatabase.execSQL(
            "UPDATE tasks SET attempts=0,requested_by='user' WHERE id=?",arrayOf(task))
    }
    fun retryDueFailures():Int{
        val now=System.currentTimeMillis()
        var queued=0
        readableDatabase.rawQuery(
            "SELECT id,attempts,updated,error FROM tasks WHERE state='error'",
            null).use{c->
            while(c.moveToNext()){
                val id=c.getLong(0)
                val attempts=c.getInt(1)
                val last=c.getLong(2)
                val error=c.getString(3).orEmpty()
                // No unbounded retry loops for invalid HTML, access denial,
                // no image payload, or a missing image on the remote provider.
                if(attempts>=4 || Regex(
                    "(?i)\\b(?:401|402|403|404|410)\\b|subscription|"+
                    "not authorized|non-image|did not return an image|"+
                    "invalid image|login|required sign-in|captcha"
                ).containsMatchIn(error))continue
                val delay=(30_000L shl attempts.coerceIn(0,6))
                    .coerceAtMost(6L*60L*60L*1000L)
                if(now-last>=delay){retry(id,false);queued++}
            }
        }
        return queued
    }
    fun count(state:String):Int=readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM tasks WHERE state=?",arrayOf(state)).use{c->
        if(c.moveToFirst())c.getInt(0) else 0}
}
