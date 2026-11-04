package com.azrael.pixivdumpsync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import java.security.MessageDigest

/** Shared file deduplication. Never confuse equal filenames with equal bytes.
 * Every key stored is a SHA-256 digest, not a signed URL, cookie or authorization token.
 */
class CrossSourceDedup(context:Context):
    SQLiteOpenHelper(context,"asterion_cross_source_dedup.sqlite",null,1){
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("""CREATE TABLE claims(
            key TEXT PRIMARY KEY,state TEXT NOT NULL,created INTEGER NOT NULL,
            manager_id INTEGER)""")
        db.execSQL("""CREATE TABLE blobs(
            sha256 TEXT PRIMARY KEY,bytes INTEGER NOT NULL,content_uri TEXT NOT NULL,
            stored INTEGER NOT NULL)""")
    }
    override fun onUpgrade(db:SQLiteDatabase,a:Int,b:Int){}
    private fun hex(data:ByteArray):String=data.joinToString(""){"%02x".format(it)}
    private fun fingerprint(value:String):String=hex(MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)))
    internal fun safeKey(address:String):String{
        require(SearchRanking.validUrl(address)){"Invalid original URL"}
        // Do not discard query params: distinct signed URLs may reference different files.
        return fingerprint(SearchRanking.canonicalKey(address))
    }
    @Synchronized fun reserve(url:String):Boolean{
        val key=safeKey(url)
        val db=writableDatabase
        db.beginTransaction()
        try{
            val existing=db.rawQuery("SELECT state,created FROM claims WHERE key=?",
                arrayOf(key)).use{c->
                if(c.moveToFirst())c.getString(0) to c.getLong(1) else null
            }
            if(existing!=null && (existing.first=="complete" ||
                System.currentTimeMillis()-existing.second<2*60*60*1000L))return false
            val row=ContentValues().apply{
                put("key",key);put("state","active")
                put("created",System.currentTimeMillis())
            }
            db.insertWithOnConflict("claims",null,row,SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
            return true
        }finally{db.endTransaction()}
    }
    /** A completed file or a transfer already accepted by DownloadManager must
     * not remain forever in the provider's pending original queue. A claim with
     * no attached transfer is NOT handled and can be retried after its lease.
     */
    @Synchronized fun alreadyHandled(url:String):Boolean{
        val key=safeKey(url)
        return readableDatabase.rawQuery(
            "SELECT state,manager_id FROM claims WHERE key=?",arrayOf(key)).use{c->
            c.moveToFirst() &&
                (c.getString(0)=="complete" || !c.isNull(1))
        }
    }
    @Synchronized fun attach(url:String,id:Long){
        writableDatabase.update("claims",ContentValues().apply{put("manager_id",id)},
            "key=?",arrayOf(safeKey(url)))
    }
    @Synchronized fun completeById(id:Long){
        writableDatabase.update("claims",ContentValues().apply{put("state","complete")},
            "manager_id=?",arrayOf(id.toString()))
    }
    @Synchronized fun releaseById(id:Long){
        writableDatabase.delete("claims","manager_id=? AND state='active'",
            arrayOf(id.toString()))
    }
    @Synchronized fun markComplete(url:String){
        writableDatabase.update("claims",ContentValues().apply{put("state","complete")},
            "key=?",arrayOf(safeKey(url)))
    }
    @Synchronized fun release(url:String){
        writableDatabase.delete("claims","key=?",arrayOf(safeKey(url)))
    }
    /** Returned URI is the original copy when the new content is byte-identical.
     * Only a newly created URI belonging to this app may be deleted as a duplicate.
     */
    @Synchronized fun registerFile(c:Context,created:Uri):Uri{
        require(created.scheme=="content"){"Only app-owned content may be deduplicated"}
        val md=MessageDigest.getInstance("SHA-256")
        var size=0L
        c.contentResolver.openInputStream(created)?.use{stream->
            val buffer=ByteArray(128*1024)
            while(true){
                val n=stream.read(buffer)
                if(n<0)break
                md.update(buffer,0,n)
                size+=n
            }
        }?:return created
        if(size<=0L)return created
        val sha=hex(md.digest())
        val existing=readableDatabase.rawQuery(
            "SELECT content_uri,bytes FROM blobs WHERE sha256=?",arrayOf(sha)).use{cur->
            if(cur.moveToFirst())cur.getString(0) to cur.getLong(1) else null
        }
        if(existing!=null && existing.second==size){
            val original=Uri.parse(existing.first)
            val reachable=runCatching{c.contentResolver.openInputStream(original)
                    ?.use{it.read()}!=null}.getOrDefault(false)
            if(original!=created && reachable){
                // Caller only registers files it just created under its own MediaStore collection.
                if(c.contentResolver.delete(created,null,null)>0)return original
            }
        }
        writableDatabase.insertWithOnConflict("blobs",null,ContentValues().apply{
            put("sha256",sha);put("bytes",size)
            put("content_uri",created.toString());put("stored",System.currentTimeMillis())
        },SQLiteDatabase.CONFLICT_REPLACE)
        return created
    }
}
