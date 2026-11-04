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
    SQLiteOpenHelper(context,"nocturne_ndus_queue.db",null,2) {
    data class Job(val category:String,val name:String,val uri:String,val attempts:Int)
    data class Status(val pending:Int,val completed:Int,val lastError:String?)
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("""CREATE TABLE jobs(
            category TEXT NOT NULL,
            name TEXT NOT NULL,
            content_uri TEXT NOT NULL,
            state TEXT NOT NULL DEFAULT 'PENDING',
            attempts INTEGER NOT NULL DEFAULT 0,
            retry_after INTEGER NOT NULL DEFAULT 0,
            last_error TEXT,
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
            arrayOf("category","name","content_uri","attempts"),
            "state='PENDING' AND retry_after<=?",
            arrayOf(System.currentTimeMillis().toString()),null,null,
            "attempts ASC,category ASC,name ASC",limit.coerceIn(1,250).toString()
        ).use { c ->
            while(c.moveToNext()) result+=Job(c.getString(0),c.getString(1),
                c.getString(2),c.getInt(3))
        }
        return result
    }
    @Synchronized fun completed(job:Job) {
        writableDatabase.update("jobs", ContentValues().apply {
            put("state","UPLOADED");putNull("last_error")
        },"category=? AND content_uri=?",arrayOf(job.category,job.uri))
    }
    @Synchronized fun failed(job:Job, reason:String) {
        val backoff=minOf(6*60*60_000L,60_000L*(1L shl minOf(job.attempts,7)))
        writableDatabase.update("jobs",ContentValues().apply {
            put("state","PENDING")
            put("attempts",job.attempts+1)
            put("retry_after",System.currentTimeMillis()+backoff)
            put("last_error",reason.take(180))
        },"category=? AND content_uri=?",arrayOf(job.category,job.uri))
    }
    /** Local metadata only; UPLOADED is written exclusively after a successful remote API result. */
    fun stateFor(category:String,uri:String):String?=readableDatabase.rawQuery(
        "SELECT state FROM jobs WHERE category=? AND content_uri=? LIMIT 1",
        arrayOf(category,uri)).use{c->if(c.moveToFirst())c.getString(0)else null}
    fun uploadedFor(uri:String):Boolean=readableDatabase.rawQuery(
        "SELECT 1 FROM jobs WHERE content_uri=? AND state='UPLOADED' LIMIT 1",
        arrayOf(uri)).use{it.moveToFirst()}

    fun status():Status {
        var pending=0;var complete=0
        readableDatabase.rawQuery("SELECT state,COUNT(*) FROM jobs GROUP BY state",null).use { c ->
            while(c.moveToNext()) if(c.getString(0)=="UPLOADED") complete+=c.getInt(1)
            else pending+=c.getInt(1)
        }
        val err=readableDatabase.rawQuery(
            "SELECT last_error FROM jobs WHERE last_error IS NOT NULL " +
                "ORDER BY retry_after DESC LIMIT 1",null).use {
            if(it.moveToFirst()) it.getString(0) else null
        }
        return Status(pending,complete,err)
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
