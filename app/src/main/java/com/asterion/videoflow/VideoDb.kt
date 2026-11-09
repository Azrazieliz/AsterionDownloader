package com.asterion.videoflow

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

data class Watch(val id: Long, val address: String, val label: String, val enabled: Boolean, val lastCheck: Long, val error: String?, val cursor: String? = null, val backfillComplete:Boolean=false)
data class Video(val id: Long, val url: String, val title: String, val status: String, val progress: Int, val savedUri: String?, val error: String?, val watchId: Long, val updated: Long = 0L)

class VideoDb(context: Context): SQLiteOpenHelper(context, "videoflow.sqlite", null, 3) {
    private val appContext = context.applicationContext
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE watches(id INTEGER PRIMARY KEY, address TEXT UNIQUE NOT NULL, label TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, last_check INTEGER NOT NULL DEFAULT 0, error TEXT, cursor TEXT, backfill_complete INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE videos(id INTEGER PRIMARY KEY, url TEXT UNIQUE NOT NULL, title TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'queued', progress INTEGER NOT NULL DEFAULT 0, saved_uri TEXT, error TEXT, watch_id INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0, retry_after INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX video_queue ON videos(state, updated)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion<2) {
            db.execSQL("ALTER TABLE videos ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE videos ADD COLUMN retry_after INTEGER NOT NULL DEFAULT 0")
        }
        if(oldVersion<3){
            db.execSQL("ALTER TABLE watches ADD COLUMN cursor TEXT")
            db.execSQL("ALTER TABLE watches ADD COLUMN backfill_complete INTEGER NOT NULL DEFAULT 0")
        }
    }
    fun addWatch(address: String, label: String = address): Boolean {
        val normalized = address.trim()
        if (!WebDiscovery.isUrl(normalized)) return false
        val row = ContentValues().apply {
            put("address", normalized); put("label", label.take(120))
        }
        return writableDatabase.insertWithOnConflict("watches", null, row, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }
    fun resetWatchBackfill(id:Long){
        writableDatabase.execSQL("UPDATE watches SET last_check=0,error=NULL,cursor=NULL,backfill_complete=0 WHERE id=?",arrayOf(id))
    }
    fun removeWatch(id: Long) { writableDatabase.delete("watches", "id=?", arrayOf("$id")) }
    fun enableWatch(id: Long, enabled: Boolean) {
        writableDatabase.update("watches", ContentValues().apply { put("enabled", if(enabled) 1 else 0) }, "id=?", arrayOf("$id"))
    }
    fun listWatches(): List<Watch> {
        val out = ArrayList<Watch>()
        readableDatabase.rawQuery("SELECT id,address,label,enabled,last_check,error,cursor,backfill_complete FROM watches ORDER BY id", null).use { c ->
            while(c.moveToNext()) out += Watch(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3)==1, c.getLong(4), c.getString(5),c.getString(6),c.getInt(7)==1)
        }
        return out
    }
    fun checked(id: Long, error: String?) {
        writableDatabase.update("watches", ContentValues().apply {
            put("last_check", System.currentTimeMillis())
            if(error==null) putNull("error") else put("error", error.take(300))
        }, "id=?", arrayOf("$id"))
    }
    fun setBackfill(id:Long,cursor:String?,complete:Boolean){
        val v=ContentValues().apply{
            if(cursor==null)putNull("cursor") else put("cursor",cursor)
            put("backfill_complete",if(complete)1 else 0)
        }
        writableDatabase.update("watches",v,"id=?",arrayOf("$id"))
    }
    fun addVideo(url: String, title: String = url, watchId: Long = 0): Boolean {
        val normalized = WebDiscovery.normalize(url) ?: return false
        val row = ContentValues().apply {
            put("url", normalized); put("title", title.take(220))
            put("watch_id", watchId); put("state", "queued")
            put("updated", System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict("videos", null, row, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }
    fun hasVideo(url:String):Boolean {
        val normalized=WebDiscovery.normalize(url)?:return false
        return readableDatabase.rawQuery(
            "SELECT 1 FROM videos WHERE url=? LIMIT 1",
            arrayOf(normalized)).use{it.moveToFirst()}
    }
    fun stateForUrl(url:String):String? {
        val normalized=WebDiscovery.normalize(url)?:return null
        return readableDatabase.rawQuery(
            "SELECT state FROM videos WHERE url=? LIMIT 1",
            arrayOf(normalized)).use{c->if(c.moveToFirst())c.getString(0)else null}
    }
    fun next(): Video? {
        val now=System.currentTimeMillis().toString()
        // Enforce explicit episode order inside ONE recognisable series only.
        // A queued, failed, paused or retry-waiting earlier episode blocks its
        // own later episode, not unrelated videos or ordinary photo albums.
        val firstUnfinished=HashMap<String,com.azrael.pixivdumpsync.SequenceOrder.Index>()
        readableDatabase.rawQuery(
            """SELECT url,title,watch_id FROM videos
               WHERE state NOT IN ('done','full_only_hold')
               ORDER BY updated ASC LIMIT 10000""",null).use{scan->
            while(scan.moveToNext()){
                val title=scan.getString(1)
                val series=com.azrael.pixivdumpsync.SequenceOrder
                    .episodeSeries(title)?:continue
                val number=com.azrael.pixivdumpsync.SequenceOrder.index(
                    com.azrael.pixivdumpsync.SequenceOrder.Kind.EPISODE,
                    title)?:continue
                val host=runCatching{java.net.URI(scan.getString(0))
                    .host.orEmpty().lowercase()}.getOrDefault("")
                val group=scan.getLong(2).toString()+"|"+host+"|"+series
                val current=firstUnfinished[group]
                if(current==null || number<current)
                    firstUnfinished[group]=number
            }
        }
        // Pausing a site's Sync AND Backfill must stop its pending transfers.
        // User-added one-off downloads (watch_id=0) remain independently usable.
        readableDatabase.rawQuery("""SELECT v.id,v.url,v.title,v.state,v.progress,
                v.saved_uri,v.error,v.watch_id,w.address
            FROM videos v LEFT JOIN watches w ON w.id=v.watch_id
            WHERE (v.state='queued' OR
                (v.state='retry_wait' AND v.retry_after<=?))
              AND (v.watch_id=0 OR w.enabled=1)
            ORDER BY CASE WHEN v.watch_id=0 THEN 0 ELSE 1 END,
                v.updated ASC LIMIT 300""",arrayOf(now)).use { c ->
            while(c.moveToNext()){
                val watchId=c.getLong(7)
                if(watchId!=0L){
                    val origin=c.getString(8)
                    if(origin.isNullOrBlank() ||
                        !com.azrael.pixivdumpsync.SiteControls.any(appContext,origin))
                        continue
                }
                val name=c.getString(2)
                val series=com.azrael.pixivdumpsync.SequenceOrder
                    .episodeSeries(name)
                if(series!=null){
                    val number=com.azrael.pixivdumpsync.SequenceOrder.index(
                        com.azrael.pixivdumpsync.SequenceOrder.Kind.EPISODE,
                        name)
                    val host=runCatching{java.net.URI(c.getString(1))
                        .host.orEmpty().lowercase()}.getOrDefault("")
                    val earliest=firstUnfinished[
                        watchId.toString()+"|"+host+"|"+series]
                    if(number!=null && earliest!=null && earliest<number)
                        continue
                }
                return Video(c.getLong(0),c.getString(1),name,
                    c.getString(3),c.getInt(4),c.getString(5),
                    c.getString(6),watchId)
            }
        }
        return null
    }
    fun listVideos(limit: Int = 100): List<Video> {
        val out=ArrayList<Video>()
        readableDatabase.rawQuery("SELECT id,url,title,state,progress,saved_uri,error,watch_id,updated FROM videos ORDER BY CASE state WHEN 'running' THEN 0 WHEN 'queued' THEN 1 ELSE 2 END, updated DESC LIMIT ${limit.coerceIn(1,1000)}", null).use { c ->
            while(c.moveToNext()) out += Video(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getInt(4),c.getString(5),c.getString(6),c.getLong(7),c.getLong(8))
        }
        return out
    }
    fun count(status: String): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM videos WHERE state=?", arrayOf(status)).use { c -> c.moveToFirst(); return c.getInt(0) }
    }
    fun update(id: Long, state: String, progress: Int = 0, savedUri: String? = null, error: String? = null) {
        val row = ContentValues().apply {
            put("state", state); put("progress", progress.coerceIn(0,100))
            put("updated", System.currentTimeMillis())
            if(savedUri != null) put("saved_uri", savedUri)
            if(error != null) put("error", error.take(700)) else putNull("error")
        }
        writableDatabase.update("videos", row, "id=?", arrayOf("$id"))
    }
    fun progress(id: Long, percent: Int) {
        writableDatabase.update("videos", ContentValues().apply { put("progress", percent.coerceIn(0,100)) }, "id=?", arrayOf("$id"))
    }
    fun pause(id:Long){
        update(id,"paused",error="Paused by user; resume to continue")
        writableDatabase.execSQL("UPDATE videos SET retry_after=0 WHERE id=?",
            arrayOf(id))
    }
    fun retry(id: Long) {
        update(id,"queued")
        writableDatabase.execSQL("UPDATE videos SET attempts=0,retry_after=0 WHERE id=?",arrayOf(id))
    }
    /** A new verified full-version reference may unblock previous shortened/unknown
     * candidates. Only matching scene titles are reconsidered, not all downloads.
     */
    fun requeueHeldByTitle(referenceTitle:String):Int {
        val key=FullLengthVideoPolicy.sceneKey(referenceTitle)
        if(key.length<12)return 0
        val matching=ArrayList<Long>()
        readableDatabase.rawQuery(
            "SELECT id,title FROM videos WHERE state='full_only_hold' LIMIT 5000",
            null).use{cursor->
            while(cursor.moveToNext()){
                if(FullLengthVideoPolicy.sceneKey(cursor.getString(1))==key)
                    matching+=cursor.getLong(0)
            }
        }
        for(id in matching)retry(id)
        return matching.size
    }
    fun scheduleRetry(id:Long,reason:String) {
        val db=writableDatabase
        val count=db.rawQuery("SELECT attempts FROM videos WHERE id=?",arrayOf(id.toString())).use{
            if(it.moveToFirst())it.getInt(0)+1 else 1
        }
        val wait=(15000L shl (count-1).coerceIn(0,6)).coerceAtMost(900000L)
        update(id,if(count<=6)"retry_wait" else "error",error=reason)
        db.execSQL("UPDATE videos SET attempts=?,retry_after=? WHERE id=?",
            arrayOf(count,System.currentTimeMillis()+wait,id))
    }
    fun recover() {
        writableDatabase.execSQL("UPDATE videos SET state='queued', progress=0 WHERE state='running'")
    }
    fun delete(id: Long) { writableDatabase.delete("videos","id=?", arrayOf("$id")) }
}
