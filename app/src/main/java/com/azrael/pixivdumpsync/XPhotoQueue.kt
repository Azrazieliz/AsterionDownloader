package com.azrael.pixivdumpsync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.net.URI

data class XSourceRecord(
    val url:String,val handle:String,val cursor:String?,val enabled:Boolean,
    val state:String,val error:String?,val updated:Long
)
data class XPhotoTask(val id:Long,val source:String,val photo:String,val state:String,
    val saved:String?,val attempts:Int,val error:String?)

class XPhotoQueue(context:Context):SQLiteOpenHelper(context,"asterion_x_photos.sqlite",null,1) {
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("""CREATE TABLE sources(
            url TEXT PRIMARY KEY, handle TEXT, cursor TEXT,
            state TEXT NOT NULL DEFAULT 'queued', last_error TEXT,
            updated INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("""CREATE TABLE photos(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            source TEXT NOT NULL, post_id TEXT NOT NULL,
            photo_url TEXT NOT NULL UNIQUE,
            status TEXT NOT NULL DEFAULT 'queued',
            saved_uri TEXT, attempts INTEGER NOT NULL DEFAULT 0,
            error TEXT, updated INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX photo_queue_idx ON photos(status,id)")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){}
    fun addSource(value:String):Boolean {
        val uri=runCatching{URI(value.trim())}.getOrNull()?:return false
        val host=uri.host.orEmpty().lowercase().removePrefix("www.")
        if(host !in setOf("x.com","twitter.com","mobile.twitter.com",
            "fxtwitter.com","vxtwitter.com"))return false
        val path=uri.path.orEmpty().trim('/')
        val handle=path.substringBefore('/')
        if(!handle.matches(Regex("[A-Za-z0-9_]{1,15}")))return false
        val row=ContentValues().apply{
            put("url",value.trim());put("handle",handle);put("updated",System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict("sources",null,row,SQLiteDatabase.CONFLICT_IGNORE)>0
    }
    fun allSources():List<XSourceRecord> {
        val out=ArrayList<XSourceRecord>()
        readableDatabase.rawQuery(
            "SELECT url,handle,cursor,state,last_error,updated FROM sources ORDER BY updated DESC",null
        ).use{c->while(c.moveToNext()){
            val state=c.getString(3)
            out.add(XSourceRecord(c.getString(0),c.getString(1).orEmpty(),c.getString(2),
                state!="disabled",state,c.getString(4),c.getLong(5)))
        }}
        return out
    }
    fun setAllEnabled(enabled:Boolean){
        writableDatabase.execSQL(
            "UPDATE sources SET state=?,updated=?",
            arrayOf(if(enabled)"queued" else "disabled",System.currentTimeMillis()))
    }
    fun setEnabled(url:String,enabled:Boolean){
        writableDatabase.execSQL("UPDATE sources SET state=?,updated=? WHERE url=?",
            arrayOf(if(enabled)"queued" else "disabled",System.currentTimeMillis(),url))
    }
    fun backfill(url:String){
        writableDatabase.execSQL(
            "UPDATE sources SET cursor=NULL,state='queued',last_error=NULL,updated=? WHERE url=?",
            arrayOf(System.currentTimeMillis(),url))
    }
    fun clearError(url:String) {
        writableDatabase.execSQL(
            "UPDATE sources SET last_error=NULL, updated=? WHERE url=?",
            arrayOf(System.currentTimeMillis(),url)
        )
    }
    fun retryFailed(url:String):Int {
        val values=ContentValues().apply{
            put("status","queued");putNull("error")
            put("updated",System.currentTimeMillis())
        }
        return writableDatabase.update("photos",values,
            "source=? AND status='error'",arrayOf(url))
    }
    fun removeSource(url:String){
        // Retain already archived photos for this source.
        writableDatabase.delete("sources","url=?",arrayOf(url))
    }
    fun sources():List<Triple<String,String,String?>> {
        val result=ArrayList<Triple<String,String,String?>>()
        readableDatabase.rawQuery(
            "SELECT url,handle,cursor FROM sources WHERE state!='disabled' ORDER BY updated ASC",null)
            .use{c->while(c.moveToNext())result.add(Triple(c.getString(0),c.getString(1),c.getString(2)))}
        return result
    }
    fun cursor(source:String,handle:String,next:String?,error:String?=null){
        val cv=ContentValues().apply{
            put("handle",handle);put("updated",System.currentTimeMillis())
            if(next==null)putNull("cursor")else put("cursor",next)
            if(error==null)putNull("last_error") else put("last_error",error.take(300))
        }
        writableDatabase.update("sources",cv,"url=?",arrayOf(source))
    }
    fun addPhoto(source:String,post:String,url:String):Boolean{
        val cv=ContentValues().apply{
            put("source",source);put("post_id",post);put("photo_url",url)
            put("updated",System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict(
            "photos",null,cv,SQLiteDatabase.CONFLICT_IGNORE)>0
    }
    fun next():XPhotoTask? {
        readableDatabase.rawQuery("""SELECT id,source,photo_url,status,saved_uri,attempts,error
            FROM photos WHERE status='queued' ORDER BY id LIMIT 1""",null).use{c->
            if(!c.moveToFirst())return null
            return XPhotoTask(c.getLong(0),c.getString(1),c.getString(2),
                c.getString(3),c.getString(4),c.getInt(5),c.getString(6))
        }
    }
    fun recover(){writableDatabase.execSQL("UPDATE photos SET status='queued' WHERE status='running'")}
    fun change(id:Long,state:String,saved:String?=null,error:String?=null){
        val cv=ContentValues().apply{
            put("status",state);put("updated",System.currentTimeMillis())
            if(saved!=null)put("saved_uri",saved)
            if(error==null)putNull("error")else put("error",error.take(500))
            if(state=="running")put("attempts",attemptCount(id)+1)
        }
        writableDatabase.update("photos",cv,"id=?",arrayOf(id.toString()))
    }
    private fun attemptCount(id:Long):Int=readableDatabase.rawQuery(
        "SELECT attempts FROM photos WHERE id=?",arrayOf(id.toString())).use{
        if(it.moveToFirst())it.getInt(0)else 0
    }
    fun retry(id:Long){change(id,"queued")}

    fun retryDueFailures():Int{
        val now=System.currentTimeMillis()
        var queued=0
        readableDatabase.rawQuery(
            "SELECT id,attempts,updated,error FROM photos WHERE status='error' AND attempts<6",
            null).use{c->
            while(c.moveToNext()){
                val id=c.getLong(0)
                val attempts=c.getInt(1)
                val age=now-c.getLong(2)
                val error=c.getString(3).orEmpty()
                if(Regex("(?i)\\b(?:401|402|403|404|410)\\b|unauthorized|not found")
                    .containsMatchIn(error))continue
                if(age>=((30_000L shl attempts.coerceIn(0,6))
                        .coerceAtMost(6L*60*60_000L))){
                    retry(id);queued++
                }
            }
        }
        return queued
    }
    fun counts():Map<String,Int>{
        val map=mutableMapOf<String,Int>()
        readableDatabase.rawQuery("SELECT status,COUNT(*) FROM photos GROUP BY status",null).use{c->
            while(c.moveToNext())map[c.getString(0)]=c.getInt(1)
        }
        return map
    }
    fun recent(limit:Int=100):List<XPhotoTask>{
        val out=ArrayList<XPhotoTask>()
        readableDatabase.rawQuery("""SELECT id,source,photo_url,status,saved_uri,attempts,error
            FROM photos ORDER BY id DESC LIMIT ${limit.coerceIn(1,1000)}""",null).use{c->
            while(c.moveToNext())out.add(XPhotoTask(c.getLong(0),c.getString(1),
                c.getString(2),c.getString(3),c.getString(4),c.getInt(5),c.getString(6)))
        }
        return out
    }
}
