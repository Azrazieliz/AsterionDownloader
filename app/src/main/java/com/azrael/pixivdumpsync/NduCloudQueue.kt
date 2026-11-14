package com.azrael.pixivdumpsync

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.azrael.galleryflow.GalleryDb

/**
 * One shared persistent outbox for Kuroha & Kyora. Downloads are never deleted,
 * nor are credentials stored in this database. Only successful create/rapid
 * upload API responses mark a file uploaded.
 */
class NduCloudQueue(context: Context) :
    SQLiteOpenHelper(context,"nocturne_ndus_queue.db",null,3) {
    data class Job(val category:String,val name:String,val uri:String,
                   val attempts:Int,val state:String)
    data class Status(val pending:Int,val completed:Int,val lastError:String?,
                      val verificationPending:Int=0,val lastVerifiedPath:String?=null)
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("""CREATE TABLE jobs(
            category TEXT NOT NULL,
            name TEXT NOT NULL,
            content_uri TEXT NOT NULL,
            state TEXT NOT NULL DEFAULT 'PENDING',
            attempts INTEGER NOT NULL DEFAULT 0,
            retry_after INTEGER NOT NULL DEFAULT 0,
            last_error TEXT,
            remote_path TEXT,remote_size INTEGER,remote_fs_id INTEGER,
            remote_md5 TEXT,verified_at INTEGER,
            PRIMARY KEY(category,content_uri)
        )""")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {
        if(oldVersion<2) {
            db.execSQL("ALTER TABLE jobs RENAME TO jobs_legacy")
            onCreate(db)
            db.execSQL("""INSERT OR IGNORE INTO jobs
                (category,name,content_uri,state,attempts,retry_after,last_error)
                SELECT category,name,content_uri,state,attempts,retry_after,last_error
                FROM jobs_legacy""")
            db.execSQL("DROP TABLE jobs_legacy")
        }
        if(oldVersion==2){
            db.execSQL("ALTER TABLE jobs ADD COLUMN remote_path TEXT")
            db.execSQL("ALTER TABLE jobs ADD COLUMN remote_size INTEGER")
            db.execSQL("ALTER TABLE jobs ADD COLUMN remote_fs_id INTEGER")
            db.execSQL("ALTER TABLE jobs ADD COLUMN remote_md5 TEXT")
            db.execSQL("ALTER TABLE jobs ADD COLUMN verified_at INTEGER")
        }
        // Old UPLOADED records had no durable remote receipt. Recheck them
        // without blindly creating a duplicate cloud file.
        if(oldVersion<3) db.execSQL(
            "UPDATE jobs SET state='VERIFY_PENDING' WHERE state='UPLOADED'")
    }

    @Synchronized fun enqueue(category:String,name:String,uri:Uri) {
        require(category in setOf("kuroha","kyora","x","comics","videos",
            "books","games","assets","koikatsu","images","torrents","creators","originals"))
        NduTeraboxClient.safeFileName(name)
        require(uri.scheme=="content") { "Only locally owned files may be queued" }
        val row=ContentValues().apply {
            put("category",category);put("name",name);put("content_uri",uri.toString())
            put("state","PENDING")
        }
        writableDatabase.insertWithOnConflict("jobs",null,row,SQLiteDatabase.CONFLICT_IGNORE)
    }
    @Synchronized fun nextBatch(limit:Int=100):List<Job> {
        val result=mutableListOf<Job>()
        readableDatabase.query("jobs",
            arrayOf("category","name","content_uri","attempts","state"),
            "state IN ('PENDING','VERIFY_PENDING') AND retry_after<=?",
            arrayOf(System.currentTimeMillis().toString()),null,null,
            "CASE WHEN state='VERIFY_PENDING' THEN 0 ELSE 1 END, attempts ASC,category ASC,name ASC",
            limit.coerceIn(1,250).toString()
        ).use { c ->
            while(c.moveToNext()) result+=Job(c.getString(0),c.getString(1),
                c.getString(2),c.getInt(3),c.getString(4))
        }
        return result
    }
    @Synchronized fun completed(job:Job,proof:NduRemoteReceipt) {
        val suffix="/"+NduTeraboxClient.safeFileName(job.name)
        require(proof.size>0L && proof.fsId>0L && proof.path.endsWith(suffix)){
            "Cloud receipt does not match the queued file"
        }
        writableDatabase.update("jobs", ContentValues().apply {
            put("state","VERIFIED");putNull("last_error")
            put("remote_path",proof.path);put("remote_size",proof.size)
            put("remote_fs_id",proof.fsId);put("remote_md5",proof.remoteMd5)
            put("verified_at",proof.verifiedAt);put("retry_after",0L)
        },"category=? AND content_uri=?",arrayOf(job.category,job.uri))
    }
    @Synchronized fun missingRemote(job:Job){
        writableDatabase.update("jobs",ContentValues().apply{
            put("state","PENDING");put("retry_after",System.currentTimeMillis()+1000L)
            put("last_error","Historical cloud copy not found; upload queued")
        },"category=? AND content_uri=? AND state='VERIFY_PENDING'",
            arrayOf(job.category,job.uri))
    }
    @Synchronized fun failed(job:Job, reason:String) {
        val backoff=minOf(6*60*60_000L,60_000L*(1L shl minOf(job.attempts,7)))
        writableDatabase.update("jobs",ContentValues().apply {
            put("state",if(job.state=="VERIFY_PENDING")"VERIFY_PENDING" else "PENDING")
            put("attempts",job.attempts+1)
            put("retry_after",System.currentTimeMillis()+backoff)
            put("last_error",reason.take(180))
        },"category=? AND content_uri=?",arrayOf(job.category,job.uri))
    }
    /** VERIFIED always has a read-after-write TeraBox receipt. */
    fun stateFor(category:String,uri:String):String?=readableDatabase.rawQuery(
        "SELECT state FROM jobs WHERE category=? AND content_uri=? LIMIT 1",
        arrayOf(category,uri)).use{c->if(c.moveToFirst())c.getString(0)else null}
    fun receiptFor(uri:String):NduRemoteReceipt?=readableDatabase.rawQuery(
        "SELECT remote_path,remote_size,remote_fs_id,remote_md5,verified_at "+
            "FROM jobs WHERE content_uri=? AND state='VERIFIED' "+
            "AND remote_path IS NOT NULL AND remote_fs_id>0 LIMIT 1",
        arrayOf(uri)).use{c->
            if(!c.moveToFirst())null
            else NduRemoteReceipt(c.getString(0),c.getLong(1),c.getLong(2),
                c.getString(3),c.getLong(4))
        }
    fun uploadedFor(uri:String):Boolean=receiptFor(uri)!=null

    fun status():Status {
        var pending=0;var complete=0;var rechecking=0
        readableDatabase.rawQuery("SELECT state,COUNT(*) FROM jobs GROUP BY state",null).use { c ->
            while(c.moveToNext()){
                val state=c.getString(0)
                if(state=="VERIFIED")complete+=c.getInt(1)
                else{
                    pending+=c.getInt(1)
                    if(state=="VERIFY_PENDING")rechecking+=c.getInt(1)
                }
            }
        }
        val err=readableDatabase.rawQuery(
            "SELECT last_error FROM jobs WHERE last_error IS NOT NULL " +
                "ORDER BY retry_after DESC LIMIT 1",null).use {
            if(it.moveToFirst()) it.getString(0) else null
        }
        val last=readableDatabase.rawQuery(
            "SELECT remote_path FROM jobs WHERE state='VERIFIED' "+
                "ORDER BY verified_at DESC LIMIT 1",null).use{
            if(it.moveToFirst())it.getString(0)else null
        }
        return Status(pending,complete,err,rechecking,last)
    }
    /** Scan only files owned by this app, never a user's whole photo library. */
    fun enqueueExisting(ctx:Context, includeKuroha:Boolean=true,
                        includeKyora:Boolean=true):Int {
        var count=0
        if(includeKuroha) {
            // Stream MediaStore rows: do not allocate a 300k+ filename list.
            val relative=android.os.Environment.DIRECTORY_DOWNLOADS + "/" +
                ctx.getString(R.string.pixiv_download_folder) + "/"
            val dirs=if(ctx.resources.getBoolean(R.bool.nocturne_preview))
                listOf(relative) else listOf(relative,"Download/PixiFlow/")
            val pattern=Regex("[0-9]+_p[0-9]+\\.(jpg|jpeg|png|gif|webp)")
            for(dir in dirs) {
                ctx.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(android.provider.BaseColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME),
                    MediaStore.MediaColumns.RELATIVE_PATH+"=?",
                    arrayOf(dir),null)?.use { c ->
                    while(c.moveToNext()) {
                        val name=c.getString(1)
                        if(name.matches(pattern)) {
                            val uri=android.content.ContentUris.withAppendedId(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI,c.getLong(0))
                            enqueue("kuroha",name,uri)
                            count++
                        }
                    }
                }
            }
        }
        if(includeKyora) GalleryDb(ctx).use { db ->
            // Iterate the MediaStore-owned content URIs, not remote download links.
            // A DB streaming cursor avoids loading a large image library in memory.
            db.readableDatabase.rawQuery(
                "SELECT filename,content_uri FROM media WHERE state='complete' " +
                    "AND content_uri IS NOT NULL AND filename IS NOT NULL " +
                    "GROUP BY filename,content_uri",null
            ).use { c ->
                while(c.moveToNext()) {
                    val name=c.getString(0)
                    val uri=runCatching { Uri.parse(c.getString(1)) }.getOrNull()
                    if(uri?.scheme=="content") {
                        runCatching { enqueue("kyora",name,uri);count++ }
                    }
                }
            }
        }
        // Flat local downloads for X, comics and VideoFlow are also uploaded.
        val ownedFolders=listOf(
            "x" to "Download/AsterionDownloader/X/",
            "comics" to "Download/AsterionDownloader/Comics/",
            "videos" to "Download/VideoFlow/"
        )
        for((category,relative) in ownedFolders) {
            ctx.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(android.provider.BaseColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME),
                MediaStore.MediaColumns.RELATIVE_PATH+"=?",
                arrayOf(relative),null)?.use { cursor->
                while(cursor.moveToNext()){
                    val name=cursor.getString(1)?:continue
                    val uri=android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,cursor.getLong(0))
                    runCatching{enqueue(category,name,uri);count++}
                }
            }
        }
        return count
    }
}
