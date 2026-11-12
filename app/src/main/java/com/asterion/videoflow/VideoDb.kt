package com.asterion.videoflow

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

data class Watch(val id: Long, val address: String, val label: String, val enabled: Boolean, val lastCheck: Long, val error: String?, val cursor: String? = null, val backfillComplete:Boolean=false)
data class Video(val id: Long, val url: String, val title: String, val status: String, val progress: Int, val savedUri: String?, val error: String?, val watchId: Long, val updated: Long = 0L, val lease:Long=0L)

class VideoDb(context: Context): SQLiteOpenHelper(context, "videoflow.sqlite", null, 5) {
    private val appContext = context.applicationContext
    init { setWriteAheadLoggingEnabled(true) }
    private val staleLeaseMs=180_000L
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE watches(id INTEGER PRIMARY KEY, address TEXT UNIQUE NOT NULL, label TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, last_check INTEGER NOT NULL DEFAULT 0, error TEXT, cursor TEXT, backfill_complete INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE videos(id INTEGER PRIMARY KEY, url TEXT UNIQUE NOT NULL, title TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'queued', progress INTEGER NOT NULL DEFAULT 0, saved_uri TEXT, error TEXT, watch_id INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0, retry_after INTEGER NOT NULL DEFAULT 0, request_origin TEXT NOT NULL DEFAULT 'user', lease INTEGER NOT NULL DEFAULT 0, heartbeat_at INTEGER NOT NULL DEFAULT 0)")
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
        if(oldVersion<4){
            db.execSQL("ALTER TABLE videos ADD COLUMN request_origin TEXT NOT NULL DEFAULT 'legacy'")
            // Preserve old transfer records/files, but do not silently resume
            // thousands of website-discovered videos never explicitly chosen.
            // Any retained job can be manually resumed with its Retry action.
            db.execSQL("""UPDATE videos SET state='paused',
                error='Older queue held; tap RETRY to authorize this file'
                WHERE request_origin='legacy' AND state IN
                    ('queued','running','retry_wait')""")
        }
        if(oldVersion<5){
            db.execSQL("ALTER TABLE videos ADD COLUMN lease INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE videos ADD COLUMN heartbeat_at INTEGER NOT NULL DEFAULT 0")
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
    /** Official source correction: retain the existing watch, toggle,
     * cursor and every saved video. No new subscription or duplicate scan.
     */
    fun migrateProviderListing(oldUrl:String,newUrl:String){
        if(oldUrl==newUrl)return
        val db=writableDatabase
        db.beginTransactionNonExclusive()
        try{
            val old=db.rawQuery("SELECT id FROM watches WHERE address=?",
                arrayOf(oldUrl)).use{if(it.moveToFirst())it.getLong(0)else null}
            if(old!=null){
                val existing=db.rawQuery("SELECT id FROM watches WHERE address=?",
                    arrayOf(newUrl)).use{if(it.moveToFirst())it.getLong(0)else null}
                if(existing==null){
                    db.execSQL("UPDATE watches SET address=? WHERE id=?",
                        arrayOf(newUrl,old))
                }else{
                    db.execSQL("UPDATE videos SET watch_id=? WHERE watch_id=?",
                        arrayOf(existing,old))
                    db.execSQL("""UPDATE watches SET
                        enabled=MAX(enabled,(SELECT enabled FROM watches WHERE id=?)),
                        last_check=MAX(last_check,(SELECT last_check FROM watches WHERE id=?)),
                        cursor=COALESCE(cursor,(SELECT cursor FROM watches WHERE id=?)),
                        backfill_complete=MAX(backfill_complete,
                            (SELECT backfill_complete FROM watches WHERE id=?))
                        WHERE id=?""",
                        arrayOf(old,old,old,old,existing))
                    db.delete("watches","id=?",arrayOf(old.toString()))
                }
            }
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    fun resetWatchBackfill(id:Long){
        writableDatabase.execSQL("UPDATE watches SET last_check=0,error=NULL,cursor=NULL,backfill_complete=0 WHERE id=?",arrayOf(id))
    }
    fun removeWatch(id: Long) { writableDatabase.delete("watches", "id=?", arrayOf("$id")) }
    /** Preserve video history and completed originals; suspend every pending
     * transfer from the provider removed by the user.
     */
    fun pauseProvider(domain:String):Int {
        val pending=ArrayList<Long>()
        readableDatabase.rawQuery(
            "SELECT id,url FROM videos WHERE state NOT IN ('done','paused')",null
        ).use { cursor ->
            while(cursor.moveToNext()) {
                val address=cursor.getString(1)
                val host=runCatching { java.net.URI(address).host.orEmpty().lowercase() }
                    .getOrDefault("")
                if(host==domain || host.endsWith("."+domain))
                    pending.add(cursor.getLong(0))
            }
        }
        for(id in pending) {
            writableDatabase.update("videos",ContentValues().apply {
                put("state","paused")
                put("error","Source retired by user; historical record preserved")
                put("lease",android.database.DatabaseUtils.longForQuery(
                    readableDatabase,"SELECT lease FROM videos WHERE id=?",
                    arrayOf(id.toString()))+1L)
            },"id=?",arrayOf(id.toString()))
        }
        return pending.size
    }
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
    fun addVideo(url: String, title: String = url, watchId: Long = 0,
                 origin:String=if(watchId==0L)"user" else "website"): Boolean {
        val normalized = WebDiscovery.normalize(url) ?: return false
        if(com.azrael.pixivdumpsync.ProviderRetirement.isRetired(normalized))return false
        val row = ContentValues().apply {
            put("url", normalized); put("title", title.take(220))
            put("watch_id", watchId); put("state", "queued")
            put("request_origin",origin.take(24))
            put("updated", System.currentTimeMillis())
        }
        val db=writableDatabase
        if(db.insertWithOnConflict("videos",null,row,
                SQLiteDatabase.CONFLICT_IGNORE)!=-1L)return true
        // A user tapping an already indexed video must be able to take over
        // the previously automated/held row and request that exact original.
        // Never replace a running transfer or overwrite a completed file.
        if(origin!="user" || watchId!=0L)return false
        val statement=db.compileStatement(
            """UPDATE videos SET request_origin='user',watch_id=0,
               state='queued',retry_after=0,error=NULL,updated=?
               WHERE url=? AND state NOT IN ('running','done')
                 AND (request_origin!='user' OR
                      state IN ('full_only_hold','retry_wait','error','paused'))""")
        statement.bindLong(1,System.currentTimeMillis())
        statement.bindString(2,normalized)
        return statement.executeUpdateDelete()==1
    }
    /** Per-row authorization: crawling a followed website is not an
     * explicit selection by the user, even if the crawl found a valid page.
     */
    fun requestOrigin(id:Long):String? =
        readableDatabase.rawQuery(
            "SELECT request_origin FROM videos WHERE id=? LIMIT 1",
            arrayOf(id.toString())).use { cursor ->
            if(cursor.moveToFirst())cursor.getString(0) else null
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
        // Only episodes actually eligible to transfer now block later
        // episodes in the same series. A paused/failed/held episode or a
        // not-yet-due retry previously prevented every subsequent episode
        // from being claimed, leaving the queue idle indefinitely.
        val firstUnfinished=HashMap<String,com.azrael.pixivdumpsync.SequenceOrder.Index>()
        readableDatabase.rawQuery(
            """SELECT url,title,watch_id FROM videos
               WHERE state IN ('queued','running')
                  OR (state='retry_wait' AND retry_after<=?)
               ORDER BY updated ASC LIMIT 10000""",
            arrayOf(now)).use{scan->
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
                v.updated ASC LIMIT 15000""",arrayOf(now)).use { c ->
            while(c.moveToNext()){
                if(com.azrael.pixivdumpsync.ProviderRetirement.isRetired(c.getString(1)))
                    continue
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
    /** Cross-process atomic lease: the two isolated video agents may never
     * take the same original. The existing next() keeps episode ordering and
     * sync/backfill eligibility; this wraps selection and claiming in one
     * SQLite write transaction.
     */
    fun claimNext():Video?{
        val db=writableDatabase
        db.beginTransactionNonExclusive()
        try{
            val next=next()
            if(next==null){db.setTransactionSuccessful();return null}
            val oldLease=android.database.DatabaseUtils.longForQuery(
                db,"SELECT lease FROM videos WHERE id=?",
                arrayOf(next.id.toString()))
            val now=System.currentTimeMillis()
            val values=ContentValues().apply{
                put("state","running");put("progress",0);put("updated",now)
                put("heartbeat_at",now);put("lease",oldLease+1L)
                putNull("error")
            }
            val updated=db.update("videos",values,
                "id=? AND (state='queued' OR (state='retry_wait' AND retry_after<=?))",
                arrayOf(next.id.toString(),now.toString()))
            db.setTransactionSuccessful()
            return if(updated==1)next.copy(status="running",lease=oldLease+1L)
                else null
        }finally{db.endTransaction()}
    }
    /** A worker must relinquish a video as soon as its lease is paused or
     * replaced. This also refreshes its crash-recovery heartbeat.
     */
    fun touchClaim(id:Long,lease:Long):Boolean{
        val row=ContentValues().apply{put("heartbeat_at",System.currentTimeMillis())}
        return writableDatabase.update("videos",row,
            "id=? AND lease=? AND state='running'",
            arrayOf(id.toString(),lease.toString()))==1
    }
    fun ownsClaim(id:Long,lease:Long):Boolean=
        readableDatabase.rawQuery(
            "SELECT 1 FROM videos WHERE id=? AND lease=? AND state='running' LIMIT 1",
            arrayOf(id.toString(),lease.toString())).use{it.moveToFirst()}

    fun completeClaim(id:Long,lease:Long,uri:String):Boolean{
        val values=ContentValues().apply{
            put("state","done");put("progress",100);put("saved_uri",uri)
            put("updated",System.currentTimeMillis());putNull("error")
        }
        return writableDatabase.update("videos",values,
            "id=? AND lease=? AND state='running'",
            arrayOf(id.toString(),lease.toString()))==1
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
        val target=readableDatabase.rawQuery(
            "SELECT url FROM videos WHERE id=?",arrayOf(id.toString())).use{
            if(it.moveToFirst())it.getString(0) else null
        }
        if(target!=null && com.azrael.pixivdumpsync.ProviderRetirement.isRetired(target))
            return
        update(id,"queued")
        writableDatabase.execSQL(
            "UPDATE videos SET attempts=0,retry_after=0,request_origin='user' WHERE id=?",
            arrayOf(id))
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
    /** A previously installed incompatible runtime failed 39 queued rows
     * with identical "Video engine unavailable" messages. Requeue precisely
     * those jobs once Python has initialized successfully, without touching
     * paused, user-rejected or completed downloads.
     */
    fun requeuePythonStartupFailures():Int {
        val now=System.currentTimeMillis()
        val statement=writableDatabase.compileStatement(
            """UPDATE videos SET state='queued',progress=0,error=NULL,
               retry_after=0,attempts=0,updated=?
               WHERE state='error' AND error LIKE 'Video engine unavailable:%'
                 AND id IN (SELECT id FROM videos
                    WHERE state='error' AND error LIKE 'Video engine unavailable:%'
                    ORDER BY updated ASC LIMIT 100)""")
        statement.bindLong(1,now)
        return statement.executeUpdateDelete()
    }
    fun scheduleRetry(id:Long,reason:String) {
        val db=writableDatabase
        val count=db.rawQuery("SELECT attempts FROM videos WHERE id=?",arrayOf(id.toString())).use{
            if(it.moveToFirst())it.getInt(0)+1 else 1
        }
        val category=VideoFailurePolicy.classify(reason)
        val requiresAction=VideoFailurePolicy.actionable(category)
        val waiting=!requiresAction && count<=VideoFailurePolicy.maxRetries(category)
        val wait=if(waiting)VideoFailurePolicy.delayMs(category,count) else 0L
        update(id,if(waiting)"retry_wait" else "error",error=reason)
        db.execSQL("UPDATE videos SET attempts=?,retry_after=? WHERE id=?",
            arrayOf(count,if(waiting)System.currentTimeMillis()+wait else 0L,id))
    }
    /** Durable due-time for retriable work; periodic polling alone makes a
     * 20-second retry wait up to 15 minutes. Scheduling can wake exactly when due.
     */
    fun nextRetryDelayMs():Long?{
        val now=System.currentTimeMillis()
        return readableDatabase.rawQuery(
            "SELECT MIN(retry_after) FROM videos WHERE state='retry_wait' AND retry_after>0",
            null).use{c->
            if(!c.moveToFirst() || c.isNull(0))null
            else(c.getLong(0)-now).coerceAtLeast(1_000L)
        }
    }
    fun recover() {
        // Never reset another live agent's download merely because a second
        // service starts. Only crashed workers with stale heartbeats recover.
        val deadline=System.currentTimeMillis()-staleLeaseMs
        writableDatabase.execSQL(
            "UPDATE videos SET state='queued', progress=0, lease=lease+1 "+
                "WHERE state='running' AND heartbeat_at<?",
            arrayOf(deadline))
    }
    fun delete(id: Long) { writableDatabase.delete("videos","id=?", arrayOf("$id")) }
}
