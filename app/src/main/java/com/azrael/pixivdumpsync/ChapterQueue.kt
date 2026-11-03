package com.azrael.pixivdumpsync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ChapterTask(val id:Long,val url:String,val title:String,val state:String,
    val progress:Int,val error:String?,val savedUri:String?,val attempts:Int)

class ChapterQueue(context:Context):SQLiteOpenHelper(context,"asterion_chapter_queue.sqlite",null,1){
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
          updated INTEGER NOT NULL)""")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){}
    fun enqueue(url:String,title:String=url):Boolean {
        val v=ContentValues().apply{
            put("url",url.trim());put("title",title.take(130));put("state","queued")
            put("updated",System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict("tasks",null,v,
            SQLiteDatabase.CONFLICT_IGNORE)>0L
    }
    fun list():List<ChapterTask>{
        val result=ArrayList<ChapterTask>()
        readableDatabase.rawQuery("""SELECT id,url,title,state,progress,error,saved_uri,attempts
            FROM tasks ORDER BY updated DESC LIMIT 1000""",null).use{c->
            while(c.moveToNext()){
                result.add(ChapterTask(c.getLong(0),c.getString(1),c.getString(2),
                    c.getString(3),c.getInt(4),c.getString(5),c.getString(6),
                    c.getInt(7)))
            }
        }
        return result
    }
    fun recover(){
        writableDatabase.execSQL("UPDATE tasks SET state='queued' WHERE state='working'")
    }
    fun next():ChapterTask?=list().asReversed().firstOrNull{it.state=="queued"}
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
    fun retry(task:Long){update(task,"queued",0)}
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
                if(Regex("(?i)\\b(?:401|402|403|404|410)\\b|subscription|not authorized")
                    .containsMatchIn(error))continue
                val delay=(30_000L shl attempts.coerceIn(0,6))
                    .coerceAtMost(6L*60L*60L*1000L)
                if(now-last>=delay){retry(id);queued++}
            }
        }
        return queued
    }
    fun count(state:String):Int=readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM tasks WHERE state=?",arrayOf(state)).use{c->
        if(c.moveToFirst())c.getInt(0) else 0}
}
