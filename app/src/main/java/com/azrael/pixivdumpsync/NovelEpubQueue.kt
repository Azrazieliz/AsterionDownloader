package com.azrael.pixivdumpsync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class NovelEpubTask(val url:String,val title:String,val attempts:Int)

/** Separate EPUB collection queue; no PDF/CBZ and no changes to manga ZIP jobs. */
class NovelEpubQueue(context:Context):
    SQLiteOpenHelper(context,"asterion_novel_epub.sqlite",null,1) {
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("""CREATE TABLE novels(
            url TEXT PRIMARY KEY,title TEXT NOT NULL,
            state TEXT NOT NULL DEFAULT 'PENDING',
            attempts INTEGER NOT NULL DEFAULT 0,retry_after INTEGER NOT NULL DEFAULT 0,
            last_update INTEGER NOT NULL DEFAULT 0,last_error TEXT,
            saved_uri TEXT)""")
        db.execSQL("CREATE INDEX novel_epub_queue ON novels(state,retry_after)")
    }
    override fun onUpgrade(db:SQLiteDatabase,old:Int,next:Int){}
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
    fun started(url:String){
        writableDatabase.update("novels",ContentValues().apply{
            put("state","WORKING");put("last_update",System.currentTimeMillis())
        },"url=?",arrayOf(url))
    }
    fun completed(url:String,uri:String){
        writableDatabase.update("novels",ContentValues().apply{
            put("state","DONE");put("saved_uri",uri)
            put("retry_after",0);putNull("last_error")
            put("last_update",System.currentTimeMillis())
        },"url=?",arrayOf(url))
    }
    fun failure(item:NovelEpubTask,error:String){
        val n=(item.attempts+1).coerceAtMost(30)
        val delay=(60_000L shl n.coerceAtMost(7)).coerceAtMost(6*60*60_000L)
        writableDatabase.update("novels",ContentValues().apply{
            put("state",if(n<12)"RETRY" else "ERROR")
            put("attempts",n)
            put("retry_after",System.currentTimeMillis()+delay)
            put("last_error",error.take(200))
            put("last_update",System.currentTimeMillis())
        },"url=?",arrayOf(item.url))
    }
}
