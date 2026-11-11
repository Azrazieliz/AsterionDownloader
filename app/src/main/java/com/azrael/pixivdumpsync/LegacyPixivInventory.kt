package com.azrael.pixivdumpsync

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Read-only bridge for Kuroha/PixiFlow ORIGINALS saved by another app.
 *
 * The previous app owns pixivdump.db and Android's sandbox forbids opening it
 * from this package. Instead, the owner authorizes its image folder with SAF.
 * Each imported page is checked for a readable, nonempty original before a
 * new request is skipped. Kuroha's folder and app database are never edited.
 */
internal object LegacyPixivFilename {
    private val pattern=Regex("""^([0-9]{4,20})_p([0-9]{1,5})\.(jpg|jpeg|png|gif|webp|avif)$""",
        RegexOption.IGNORE_CASE)
    data class Page(val illustId:String,val index:Int)
    fun parse(filename:String):Page? {
        val match=pattern.matchEntire(filename)?:return null
        val page=match.groupValues[2].toIntOrNull()?:return null
        if(page !in 0..9999)return null
        return Page(match.groupValues[1],page)
    }
}
internal object LegacyPixivInventory {
    private const val PREF="asterion_kuroha_pixiv_inventory_v1"
    private const val MAX_FILES=200_000
    private val refreshing=AtomicBoolean(false)
    private class Db(ctx:Context):SQLiteOpenHelper(ctx,
        "asterion_legacy_pixiv_inventory.sqlite",null,1){
        override fun onCreate(db:SQLiteDatabase){
            db.execSQL("""CREATE TABLE originals(
                illust_id TEXT NOT NULL,page_index INTEGER NOT NULL,
                content_uri TEXT NOT NULL, source_uri TEXT NOT NULL,
                filename TEXT NOT NULL,size INTEGER NOT NULL,
                seen INTEGER NOT NULL,
                PRIMARY KEY(content_uri))""")
            db.execSQL("CREATE INDEX legacy_pixiv_page ON originals(illust_id,page_index)")
            db.execSQL("CREATE INDEX legacy_pixiv_source ON originals(source_uri,seen)")
        }
        override fun onUpgrade(db:SQLiteDatabase,old:Int,next:Int){}
        fun importPage(page:LegacyPixivFilename.Page,name:String,
                       uri:Uri,source:String,size:Long,seen:Long){
            writableDatabase.insertWithOnConflict("originals",null,
                ContentValues().apply {
                    put("illust_id",page.illustId);put("page_index",page.index)
                    put("filename",name);put("content_uri",uri.toString())
                    put("source_uri",source);put("size",size);put("seen",seen)
                },SQLiteDatabase.CONFLICT_REPLACE)
        }
        fun pageUris(id:String,index:Int):List<String>{
            val found=ArrayList<String>()
            readableDatabase.rawQuery(
                "SELECT content_uri FROM originals WHERE illust_id=? AND page_index=?"+
                " ORDER BY seen DESC LIMIT 8",
                arrayOf(id,index.toString())).use{c->
                while(c.moveToNext())found+=c.getString(0)
            }
            return found
        }
        fun count():Int=readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM originals",null).use{c->
            if(c.moveToFirst())c.getInt(0) else 0
        }
        fun purge(source:String,seen:Long){
            writableDatabase.delete("originals","source_uri=? AND seen!=?",
                arrayOf(source,seen.toString()))
        }
    }
    data class Status(val savedPages:Int,val linkedFolders:Int,
                      val lastError:String?,val scanning:Boolean)
    private fun prefs(ctx:Context)=ctx.getSharedPreferences(PREF,0)
    fun status(ctx:Context):Status=Db(ctx).use{db->
        Status(db.count(),prefs(ctx).getStringSet("trees",emptySet())
            .orEmpty().size,prefs(ctx).getString("error",null),refreshing.get())
    }
    /** UI selects one folder at a time; previously linked folders are retained. */
    fun addTree(ctx:Context,tree:Uri){
        require(DocumentsContract.isTreeUri(tree)){"Select an image folder"}
        ctx.contentResolver.takePersistableUriPermission(
            tree,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val old=prefs(ctx).getStringSet("trees",emptySet()).orEmpty()
        prefs(ctx).edit().putStringSet("trees",old.plus(tree.toString())).apply()
    }
    /** Some SAF providers report an unknown byte length (-1), even when
     * the original is readable. Probe only its first byte in that case.
     */
    private fun readableOriginal(ctx:Context,uri:Uri):Boolean=runCatching {
        ctx.contentResolver.openFileDescriptor(uri,"r")?.use{file->
            if(file.statSize>0L)true
            else if(file.statSize==0L)false
            else java.io.FileInputStream(file.fileDescriptor).use{it.read()!=-1}
        }?:false
    }.getOrDefault(false)
    /** Only verified files suppress a network request, never a DB row alone. */
    fun hasPage(ctx:Context,id:String,index:Int):Boolean{
        if(id.isBlank()||index<0)return false
        return Db(ctx).use{it.pageUris(id,index)}.any{
            readableOriginal(ctx,Uri.parse(it))
        }
    }
    private fun readTree(ctx:Context,tree:Uri):Int{
        val provider=ctx.contentResolver
        val root=DocumentsContract.getTreeDocumentId(tree)
        val queue=ArrayDeque<Pair<String,Int>>()
        val visited=HashSet<String>()
        queue.add(root to 0)
        val seen=System.currentTimeMillis()
        var files=0
        Db(ctx).use{db->
            // Bulk transactions keep large historical Pixiv folders fast.
            // Failed scans roll back instead of marking a partial index complete.
            val writable=db.writableDatabase
            writable.beginTransactionNonExclusive()
            try {
            while(queue.isNotEmpty()){
                val (parent,depth)=queue.removeFirst()
                if(!visited.add(parent))continue
                require(depth<=5){"Choose the Kuroha image folder directly"}
                val uri=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent)
                val columns=arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE)
                val rows=provider.query(uri,columns,null,null,null)
                    ?:error("Selected folder is not readable")
                rows.use{c->
                    while(c.moveToNext()){
                        val id=c.getString(0)?:continue
                        val filename=c.getString(1)?:continue
                        val mime=c.getString(2).orEmpty()
                        if(mime==DocumentsContract.Document.MIME_TYPE_DIR){
                            if(depth<5)queue.add(id to depth+1)
                            continue
                        }
                        val page=LegacyPixivFilename.parse(filename)?:continue
                        val reported=if(c.isNull(3))-1L else c.getLong(3)
                        val original=DocumentsContract.buildDocumentUriUsingTree(tree,id)
                        if(reported==0L)continue
                        val size=if(reported>0L)reported else
                            if(readableOriginal(ctx,original))1L else 0L
                        if(size<=0L)continue
                        db.importPage(page,filename,original,tree.toString(),size,seen)
                        files++
                        require(files<MAX_FILES){"Legacy folder exceeds safe index size"}
                    }
                }
            }
            // A completed scan is authoritative for this source. A failed
            // scan never deletes earlier verified receipts.
            db.purge(tree.toString(),seen)
            writable.setTransactionSuccessful()
            } finally { writable.endTransaction() }
        }
        return files
    }
    /** MediaStore only exposes other apps' downloads when Android allows it.
     * The SAF path remains the reliable, owner-authorized import mechanism.
     */
    private fun readVisible(ctx:Context):Int{
        val columns=arrayOf(MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,MediaStore.Downloads.SIZE)
        val selection=MediaStore.Downloads.RELATIVE_PATH+" IN (?,?)"
        val paths=arrayOf(
            Environment.DIRECTORY_DOWNLOADS+"/Kuroha/",
            Environment.DIRECTORY_DOWNLOADS+"/PixiFlow/")
        val seen=System.currentTimeMillis()
        var count=0
        val root=MediaStore.Downloads.EXTERNAL_CONTENT_URI
        ctx.contentResolver.query(root,columns,selection,paths,null)?.use{c->
            Db(ctx).use{db->
                while(c.moveToNext()&&count<MAX_FILES){
                    val name=c.getString(1)?:continue
                    val page=LegacyPixivFilename.parse(name)?:continue
                    val size=if(c.isNull(2))0L else c.getLong(2)
                    if(size<=0L)continue
                    val uri=android.content.ContentUris.withAppendedId(root,c.getLong(0))
                    db.importPage(page,name,uri,"visible:"+name.substringAfterLast('.'),size,seen)
                    count++
                }
            }
        }
        return count
    }
    fun refresh(ctx:Context):Int {
        val app=ctx.applicationContext
        var count=0
        var failed=0
        var lastError=""
        for(raw in prefs(app).getStringSet("trees",emptySet()).orEmpty()){
            try{count+=readTree(app,Uri.parse(raw))}
            catch(t:Exception){failed++;lastError=t.message?:t.javaClass.simpleName}
        }
        count+=runCatching{readVisible(app)}.getOrDefault(0)
        val editor=prefs(app).edit().putString("error",if(failed==0)null
            else "$failed folder(s) unavailable: "+lastError.take(160))
        if(failed==0)editor.putLong("checked",System.currentTimeMillis())
        else editor.putLong("checked",0L)
        editor.apply()
        return count
    }
    fun refreshIfDue(ctx:Context,intervalMs:Long=12L*60*60*1000):Int{
        if(System.currentTimeMillis()-prefs(ctx).getLong("checked",0L)<intervalMs)
            return status(ctx).savedPages
        if(!refreshing.compareAndSet(false,true))return status(ctx).savedPages
        return try{refresh(ctx)}
        finally{refreshing.set(false)}
    }
    fun refreshAsync(ctx:Context,onComplete:((Int,Throwable?)->Unit)?=null){
        if(!refreshing.compareAndSet(false,true))return
        val app=ctx.applicationContext
        thread(name="asterion-kuroha-legacy-inventory"){
            var n=0
            val failure=runCatching{n=refresh(app)}.exceptionOrNull()
            refreshing.set(false)
            onComplete?.invoke(n,failure)
        }
    }
}
