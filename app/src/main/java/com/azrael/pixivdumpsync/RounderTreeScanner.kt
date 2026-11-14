package com.azrael.pixivdumpsync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.DocumentsContract
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Read the same SAF collection tree as Rounder, when Android's provider grants
 * access. Raw paths/sizes are indexed, never interpreted as a named volume
 * until the user supplies the actual filename convention.
 */
object RounderTreeScanner {
    private const val PREF="asterion_rounder_document_tree"
    private val running=AtomicBoolean(false)
    private class Db(ctx:Context):SQLiteOpenHelper(ctx,"rounder_tree_index.sqlite",null,1){
        override fun onCreate(db:SQLiteDatabase){
            db.execSQL("""CREATE TABLE dirs(
                id TEXT PRIMARY KEY,path TEXT NOT NULL,
                processed INTEGER NOT NULL DEFAULT 0)""")
            db.execSQL("""CREATE TABLE files(
                id TEXT PRIMARY KEY,path TEXT NOT NULL,name TEXT NOT NULL,
                mime TEXT,size INTEGER,modified INTEGER,seen INTEGER NOT NULL)""")
        }
        override fun onUpgrade(db:SQLiteDatabase,old:Int,next:Int){}
        fun nextDir():Pair<String,String>?=readableDatabase.rawQuery(
            "SELECT id,path FROM dirs WHERE processed=0 LIMIT 1",null).use{c->
                if(c.moveToFirst())c.getString(0) to c.getString(1) else null
            }
        fun addDir(id:String,path:String){
            writableDatabase.insertWithOnConflict("dirs",null,
                ContentValues().apply{put("id",id);put("path",path)},
                SQLiteDatabase.CONFLICT_IGNORE)
        }
        fun markDir(id:String){
            writableDatabase.update("dirs",ContentValues().apply{put("processed",1)},
                "id=?",arrayOf(id))
        }
        fun addFile(id:String,path:String,name:String,mime:String,size:Long,
                    modified:Long){
            writableDatabase.insertWithOnConflict("files",null,
                ContentValues().apply{
                    put("id",id);put("path",path);put("name",name)
                    put("mime",mime);put("size",size);put("modified",modified)
                    put("seen",System.currentTimeMillis())
                },SQLiteDatabase.CONFLICT_REPLACE)
        }
        fun count(sql:String):Int=readableDatabase.rawQuery(sql,null).use{c->
            if(c.moveToFirst())c.getInt(0) else 0
        }
        fun reset(){
            writableDatabase.delete("files",null,null)
            writableDatabase.delete("dirs",null,null)
        }
    }
    data class Status(val files:Int,val pendingDirectories:Int,
                      val lastError:String?)
    fun tree(ctx:Context):Uri?=ctx.getSharedPreferences(PREF,0)
        .getString("uri",null)?.let(Uri::parse)
    fun selectTree(ctx:Context,uri:Uri){
        val resolver=ctx.contentResolver
        resolver.takePersistableUriPermission(uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val previous=tree(ctx)?.toString()
        ctx.getSharedPreferences(PREF,0).edit().putString("uri",uri.toString())
            .remove("error").apply()
        Db(ctx).use{db->
            if(previous!=uri.toString())db.reset()
            db.addDir(DocumentsContract.getTreeDocumentId(uri),"")
        }
    }
    fun status(ctx:Context):Status=Db(ctx).use{db->
        Status(db.count("SELECT COUNT(*) FROM files"),
            db.count("SELECT COUNT(*) FROM dirs WHERE processed=0"),
            ctx.getSharedPreferences(PREF,0).getString("error",null))
    }
    fun scanAsync(ctx:Context){
        if(!running.compareAndSet(false,true))return
        thread(name="asterion-rounder-tree-scan"){
            try{scan(ctx.applicationContext)}
            finally{running.set(false)}
        }
    }
    private fun scan(ctx:Context){
        BackfillPerformance.backgroundWorker()
        val root=tree(ctx)?:return
        val pref=ctx.getSharedPreferences(PREF,0)
        try{
            Db(ctx).use{db->
                db.addDir(DocumentsContract.getTreeDocumentId(root),"")
                var scannedDirs=0
                var scannedFiles=0
                while(scannedDirs<240 && scannedFiles<30000){
                    val (dir,path)=db.nextDir()?:break
                    val children=DocumentsContract.buildChildDocumentsUriUsingTree(root,dir)
                    val cols=arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    val cursor=ctx.contentResolver.query(children,cols,null,null,null)
                        ?:error("Rounder folder provider did not return children")
                    cursor.use{c->
                        while(c.moveToNext()){
                            val id=c.getString(0)?:continue
                            val name=c.getString(1)?:continue
                            val mime=c.getString(2).orEmpty()
                            val childPath=path+"/"+name
                            if(mime==DocumentsContract.Document.MIME_TYPE_DIR)
                                db.addDir(id,childPath)
                            else{
                                db.addFile(id,childPath,name,mime,
                                    if(c.isNull(3))-1L else c.getLong(3),
                                    if(c.isNull(4))0L else c.getLong(4))
                                scannedFiles++
                            }
                        }
                    }
                    db.markDir(dir)
                    scannedDirs++
                }
            }
            pref.edit().remove("error").apply()
        }catch(t:Exception){
            pref.edit().putString("error",
                (t.message?:t.javaClass.simpleName).take(220)).apply()
        }
    }
}
