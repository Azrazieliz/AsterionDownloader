package com.asterion.videoflow

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared durable multi-process download-engine event stream.
 * Called by the EXISTING VideoTransferJournal, with zero extra network calls.
 * Stores only numeric job IDs, stage, safe error classes and verified byte counts;
 * never stores URLs, titles, cookies, headers or original media.
 */
internal object DownloaderEventJournal {
    private const val MAX_ROWS=12000
    private class Store(context:Context):
        SQLiteOpenHelper(context.applicationContext,"asterion_download_events_v1.sqlite",null,1) {
        init { setWriteAheadLoggingEnabled(true) }
        override fun onCreate(db:SQLiteDatabase){
            db.execSQL("""CREATE TABLE events(
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp_utc_ms INTEGER NOT NULL,
                process_uptime_ms INTEGER NOT NULL,
                pid INTEGER NOT NULL,
                thread_id INTEGER NOT NULL,
                video_id INTEGER NOT NULL,
                stage TEXT NOT NULL,
                result_code TEXT NOT NULL,
                verified_bytes INTEGER NOT NULL
            )""".trimIndent())
            db.execSQL("CREATE INDEX idx_downloader_job_events ON events(video_id,seq)")
        }
        override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){
            error("Refusing destructive download event history migration")
        }
    }
    private val guard=Any()
    private fun safe(s:String)=s.lowercase().take(60)
        .takeIf{it.matches(Regex("[a-z0-9_.:-]+"))} ?: "redacted"
    fun record(context:Context,id:Long,stage:String,resultCode:String="observed",
               verifiedBytes:Long=0L){
        // Observability is best-effort. Never sabotage the original download.
        runCatching{
            synchronized(guard){
                Store(context).use{helper->
                    val db=helper.writableDatabase
                    val row=ContentValues().apply{
                        put("timestamp_utc_ms",System.currentTimeMillis())
                        put("process_uptime_ms",SystemClock.elapsedRealtime())
                        put("pid",android.os.Process.myPid())
                        put("thread_id",Thread.currentThread().id)
                        put("video_id",id.coerceAtLeast(0))
                        put("stage",safe(stage))
                        put("result_code",safe(resultCode))
                        put("verified_bytes",verifiedBytes.coerceAtLeast(0))
                    }
                    db.insertOrThrow("events",null,row)
                    val latest=android.database.DatabaseUtils.longForQuery(
                        db,"SELECT MAX(seq) FROM events",null)
                    if(latest%128L==0L)
                        db.execSQL("DELETE FROM events WHERE seq <= ?",
                            arrayOf(latest-MAX_ROWS))
                }
            }
        }
    }
    fun recent(context:Context,since:Long,limit:Int=30):JSONObject{
        require(since>=0L && limit in 1..30){"Invalid download event cursor"}
        val result=JSONArray()
        var cursor=since
        var oldest=0L
        var latest=0L
        Store(context).use{helper->
            val db=helper.readableDatabase
            db.rawQuery("SELECT COALESCE(MIN(seq),0),COALESCE(MAX(seq),0) FROM events",
                null).use{
                if(it.moveToFirst()){oldest=it.getLong(0);latest=it.getLong(1)}
            }
            db.rawQuery("SELECT seq,timestamp_utc_ms,process_uptime_ms,pid,"+
                "thread_id,video_id,stage,result_code,verified_bytes "+
                "FROM events WHERE seq>? ORDER BY seq ASC LIMIT ?",
                arrayOf(since.toString(),limit.toString())).use{rows->
                while(rows.moveToNext()){
                    cursor=rows.getLong(0)
                    result.put(JSONObject()
                        .put("seq",cursor)
                        .put("utc_ms",rows.getLong(1))
                        .put("uptime_ms",rows.getLong(2))
                        .put("pid",rows.getInt(3))
                        .put("thread_id",rows.getLong(4))
                        .put("video_id",rows.getLong(5))
                        .put("stage",rows.getString(6))
                        .put("result_code",rows.getString(7))
                        .put("verified_bytes",rows.getLong(8)))
                }
            }
        }
        return JSONObject().put("schema_version",1)
            .put("events",result).put("next_sequence",cursor)
            .put("oldest_sequence",oldest).put("latest_sequence",latest)
            .put("dropped_before_cursor",
                if(since>0 && oldest>since+1)oldest-since-1 else 0)
            .put("scope","sanitized_download_stages_only")
    }
}
