package com.azrael.pixivdumpsync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/**
 * Server-side TeraBox acquisition of public original file URLs. The phone
 * sends only the URL and cloud destination; no file media is written to local
 * storage by this lane.
 *
 * Pages, player embeds, HLS manifests, private/session downloads and locally
 * assembled EPUB/ZIP are NOT eligible. They require a verified publisher
 * original and cloud-capable packaging first. Never fall back to device save
 * just because this cloud lane reports an unsupported source.
 */
internal object TeraBoxRemoteOriginals {
    private val extensions=setOf("png","jpg","jpeg","gif","webp","avif",
        "mp4","m4v","mkv","webm","mov","mp3","flac","m4a","wav",
        "opus","ogg","zip","epub","cbz","7z","rar","pdf",
        "blend","fbx","obj","glb","gltf","stl","pmx","pmd","vmd",
        "vpd","vrm","moc3","psd","kra","clip","unitypackage",
        "pak","xp3","rpa","rpy","apk","torrent")
    private val dnsName=Regex("""(?i)^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,30}$""")
    private val privateName=Regex("""(?i)(?:^localhost$|\.localhost$|\.local$|\.internal$|\.lan$|\.home$|\.test$|\.invalid$)""")
    private val authParameters=Regex("""(?i)(?:^|[&;])(?:username|password|passwd|access_token|cookie|authorization)=""")
    fun verifiedPublicFileLink(url:String):Boolean=runCatching {
        val value=URI(url)
        val host=value.host.orEmpty().lowercase(Locale.ROOT)
        if(value.scheme!="https" || value.userInfo!=null ||
            value.fragment!=null || value.rawFragment!=null ||
            value.port !in setOf(-1,443) || url.length !in 16..3500 ||
            !dnsName.matches(host) || privateName.containsMatchIn(host) ||
            value.rawPath.orEmpty().contains("..") ||
            authParameters.containsMatchIn(value.rawQuery.orEmpty()))
            return@runCatching false
        val extension=value.path.orEmpty().substringAfterLast('.').lowercase(Locale.ROOT)
        extension in extensions && value.path.orEmpty().substringAfterLast('/')
            .length in 5..200
    }.getOrDefault(false)
    fun safeName(url:String,explicit:String?=null):String {
        require(verifiedPublicFileLink(url)){"Not a public original HTTPS file URL"}
        val path=URI(url).path.orEmpty()
        val suggested=explicit?.takeIf{value->
            value.substringAfterLast('.').lowercase(Locale.ROOT) in extensions
        }?:path.substringAfterLast('/')
        val normalized=suggested.replace(Regex("""[^A-Za-z0-9._-]"""),"_")
            .take(160)
        return NduTeraboxClient.safeFileName(normalized)
    }
    fun identity(url:String):String {
        require(verifiedPublicFileLink(url)){"Remote cloud URL not eligible"}
        return MessageDigest.getInstance("SHA-256").digest(
            url.toByteArray(Charsets.UTF_8)).joinToString(""){
            "%02x".format(it.toInt() and 255)
        }
    }
    /** A file is not cloud-verified by an HTTP 200 task submission alone. */
    fun matchingTask(records:org.json.JSONObject,
                     expectedId:String?,expectedUrl:String):Boolean {
        val data=records.optJSONObject("data")?:return false
        val list=data.optJSONArray("list")?:data.optJSONArray("records")
            ?:return false
        for(i in 0 until list.length()){
            val row=list.optJSONObject(i)?:continue
            val ids=listOf(row.optString("task_id"),
                row.optString("taskid"),row.optString("id"))
            if(expectedId!=null && ids.any{it.isNotBlank() && it==expectedId})
                return true
            // Some older TeraBox remote-import responses carry no task id.
            // Require the exact source URL and the remote destination path
            // before independent filemeta evidence can count as a receipt.
            if(expectedId==null && listOf(row.optString("url"),
                row.optString("source_url"),row.optString("urls"))
                    .any{it==expectedUrl})return true
        }
        return false
    }
}

internal class TeraBoxRemoteQueue(ctx:Context):
    SQLiteOpenHelper(ctx.applicationContext,
        "asterion_terabox_remote_originals.sqlite",null,1) {

    data class Record(val id:String,val encryptedUrl:String,
                      val category:String,val filename:String,
                      val destination:String,val state:String,
                      val taskId:String?,val attempts:Int,
                      val retryAfter:Long)
    /** Conservative TeraBox server-side transfer admission; URL imports are
     * truly remote, not simultaneously buffered on the Android device. */
    companion object { const val MAX_REMOTE_IMPORTS=4 }
    data class Summary(val pending:Int,val active:Int,
                       val verified:Int,val failed:Int)

    /** Safe display metadata: signed origin URLs never appear in UI. */
    data class Display(val id:String,val category:String,val filename:String,
                       val state:String,val error:String?,val updatedAt:Long)
    fun recent(limit:Int=100):List<Display>{
        val found=ArrayList<Display>()
        readableDatabase.rawQuery(
            "SELECT id,category,filename,state,last_error,updated_at "+
                "FROM remote_originals WHERE state!='CANCELLED' "+
                "ORDER BY updated_at DESC LIMIT ?",
            arrayOf(limit.coerceIn(1,300).toString())).use{c->
            while(c.moveToNext())found.add(Display(c.getString(0),
                c.getString(1),c.getString(2),c.getString(3),
                c.getString(4),c.getLong(5)))
        }
        return found
    }
    /** Claim remains unique across all modules before any TeraBox import. */
    fun pauseQueued(id:String):Boolean =
        writableDatabase.update("remote_originals",
            ContentValues().apply{
                put("state","PAUSED");put("updated_at",System.currentTimeMillis())
            },"id=? AND state='QUEUED'",arrayOf(id))>0
    fun resume(id:String):Boolean =
        writableDatabase.update("remote_originals",
            ContentValues().apply{
                put("state","QUEUED");put("retry_after",0L)
                putNull("last_error");put("updated_at",System.currentTimeMillis())
            },"id=? AND state IN ('PAUSED','ERROR')",arrayOf(id))>0
    /** Already SUBMITTED remote tasks cannot honestly be cancelled here.
     * Cancelling local queue rows would only hide ongoing cloud work.
     */
    fun cancelUnsubmitted(id:String):Boolean =
        writableDatabase.update("remote_originals",
            ContentValues().apply{
                put("state","CANCELLED");put("updated_at",System.currentTimeMillis())
            },"id=? AND state IN ('QUEUED','PAUSED','ERROR')",
            arrayOf(id))>0
    fun nextDueDelayMs(now:Long=System.currentTimeMillis()):Long? =
        readableDatabase.rawQuery(
            "SELECT MIN(retry_after) FROM remote_originals "+
                "WHERE state IN ('QUEUED','SUBMITTED')",null).use{c->
            if(!c.moveToFirst()||c.isNull(0))null
            else (c.getLong(0)-now).coerceAtLeast(1000L)
        }
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("""CREATE TABLE remote_originals(
            id TEXT PRIMARY KEY,
            url_cipher TEXT NOT NULL,
            category TEXT NOT NULL,
            filename TEXT NOT NULL,
            destination TEXT NOT NULL,
            state TEXT NOT NULL DEFAULT 'QUEUED',
            task_id TEXT,
            attempts INTEGER NOT NULL DEFAULT 0,
            retry_after INTEGER NOT NULL DEFAULT 0,
            last_error TEXT,
            remote_path TEXT,
            remote_size INTEGER,
            remote_fs_id INTEGER,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )""")
        db.execSQL("CREATE INDEX remote_original_state ON remote_originals(state,retry_after)")
    }
    override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int){
        throw IllegalStateException("Unknown cloud queue migration $old -> $new")
    }
    @Synchronized fun enqueue(ctx:Context,url:String,category:String,
                              explicitName:String?=null):Boolean{
        require(category in setOf("kyora","x","comics","videos","books",
            "games","assets","koikatsu","images","torrents",
            "creators","originals","kuroha")){"Unknown remote destination module"}
        check(NduSettings.configured(ctx)){"Connect TeraBox before queuing cloud originals"}
        require(TeraBoxRemoteOriginals.verifiedPublicFileLink(url)){
            "This link is not a public original file; cloud-only transfer requires a resolved file URL"
        }
        // URL-based reservation occurs *before* the backend downloads bytes,
        // even when two modules request the same original simultaneously.
        val id=TeraBoxRemoteOriginals.identity(url)
        // Remote TeraBox chooses the source filename; an unrelated article
        // title cannot rename the remote task's output or verify its receipt.
        val name=TeraBoxRemoteOriginals.safeName(url)
        val encrypted=DirectRetryVault.protect(url)
        val destination=NduSettings.destination(ctx,category)+"/"+id.take(12)
        val row=ContentValues().apply{
            put("id",id);put("url_cipher",encrypted);put("category",category)
            put("filename",name);put("destination",destination)
            put("state","QUEUED");put("created_at",System.currentTimeMillis())
            put("updated_at",System.currentTimeMillis())
        }
        val inserted=writableDatabase.insertWithOnConflict("remote_originals",
            null,row,SQLiteDatabase.CONFLICT_IGNORE)>0
        if(!inserted){
            // Explicitly re-selecting a cancelled original reuses its
            // canonical reservation. Never duplicate an in-flight task.
            writableDatabase.update("remote_originals",
                ContentValues().apply{
                    put("state","QUEUED");put("retry_after",0L)
                    put("updated_at",System.currentTimeMillis())
                },"id=? AND state='CANCELLED'",arrayOf(id))
        }
        return inserted
    }
    fun summary():Summary{
        val found=HashMap<String,Int>()
        readableDatabase.rawQuery(
            "SELECT state,COUNT(*) FROM remote_originals GROUP BY state",
            null).use{c->
            while(c.moveToNext())found[c.getString(0)]=c.getInt(1)
        }
        return Summary(found.getOrDefault("QUEUED",0),
            found.getOrDefault("SUBMITTED",0),
            found.getOrDefault("VERIFIED",0),
            found.getOrDefault("ERROR",0))
    }
    private fun pending(limit:Int):List<Record>{
        val out=ArrayList<Record>()
        val query="""SELECT id,url_cipher,category,filename,destination,state,
            task_id,attempts,retry_after
            FROM remote_originals
            WHERE state IN ('QUEUED','SUBMITTED') AND retry_after<=?
            ORDER BY CASE WHEN state='SUBMITTED' THEN 0 ELSE 1 END,
                created_at ASC LIMIT ?"""
        readableDatabase.rawQuery(query,arrayOf(
            System.currentTimeMillis().toString(),limit.coerceIn(1,100).toString()
        )).use{c->
            while(c.moveToNext())out.add(Record(c.getString(0),c.getString(1),
                c.getString(2),c.getString(3),c.getString(4),c.getString(5),
                c.getString(6),c.getInt(7),c.getLong(8)))
        }
        return out
    }
    @Synchronized private fun update(id:String,state:String,taskId:String?=null,
                                    receipt:NduRemoteReceipt?=null,
                                    error:String?=null,attempt:Int=0,
                                    retryAfter:Long=0L){
        writableDatabase.update("remote_originals",ContentValues().apply{
            put("state",state);put("retry_after",retryAfter)
            put("attempts",attempt);put("updated_at",System.currentTimeMillis())
            if(taskId!=null)put("task_id",taskId)
            if(error!=null)put("last_error",error.take(150))
            else putNull("last_error")
            if(receipt!=null){
                put("remote_path",receipt.path)
                put("remote_size",receipt.size)
                put("remote_fs_id",receipt.fsId)
            }
        },"id=?",arrayOf(id))
    }
    private fun minorNetwork(error:Throwable):Boolean {
        val reason=error.message.orEmpty().lowercase(Locale.ROOT)
        return Regex("""(?:timed?\s*out|timeout|connection reset|connection refused|dns|unknownhost|temporary unavailable|broken pipe|http\s+(?:408|425|429|500|502|503|504))""")
            .containsMatchIn(reason)
    }
    /** One app-owned scheduled execution at a time. The backend performs
     * actual source fetching; this loop sends requests and checks receipts.
     */
    fun drain(ctx:Context,limit:Int=24,stop:()->Boolean={false}):Summary{
        if(!NduSettings.configured(ctx))return summary()
        synchronized(admission){
            if(active)return summary()
            active=true
        }
        try{
            val list=pending(limit)
            if(list.isEmpty())return summary()
            val cookie=NduSettings.cookie(ctx)?:return summary()
            val client=NduTeraboxClient(cookie,NduSettings.domain(ctx))
            client.connect()
            val records=runCatching{client.remoteImportRecords()}.getOrNull()
            var submitted=readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM remote_originals WHERE state='SUBMITTED'",
                null).use{c->if(c.moveToFirst())c.getInt(0)else 0}
            for(job in list){
                if(stop())break
                try{
                    val address=DirectRetryVault.reveal(job.encryptedUrl)
                    require(TeraBoxRemoteOriginals.verifiedPublicFileLink(address) &&
                        TeraBoxRemoteOriginals.identity(address)==job.id){
                        "Encrypted cloud original identity no longer matches"
                    }
                    val remotePath=job.destination+"/"+job.filename
                    if(job.state=="QUEUED"){
                        if(submitted>=MAX_REMOTE_IMPORTS)continue
                        // An existing unknown cloud file is NOT evidence of
                        // this selected URL. Never overwrite or duplicate it.
                        check(client.remoteOriginalReceipt(remotePath)==null){
                            "Cloud destination already occupied; identity unverified"
                        }
                        val ticket=client.submitRemoteOriginal(
                            address,job.destination)
                        update(job.id,"SUBMITTED",ticket.taskId,
                            attempt=job.attempts,
                            retryAfter=System.currentTimeMillis()+60_000L)
                        submitted++
                        continue
                    }
                    // Full cloud receipt needs a matching server-side task
                    // AND actual metadata read-after-write at the unique
                    // per-original cloud folder. No task acceptance shortcuts.
                    val current=records?:client.remoteImportRecords()
                    val linked=TeraBoxRemoteOriginals.matchingTask(
                        current,job.taskId,address)
                    if(!linked){
                        update(job.id,"SUBMITTED",job.taskId,
                            attempt=job.attempts,
                            retryAfter=System.currentTimeMillis()+60_000L)
                        continue
                    }
                    val receipt=client.remoteOriginalReceipt(remotePath)
                    if(receipt==null){
                        update(job.id,"SUBMITTED",job.taskId,
                            attempt=job.attempts,
                            retryAfter=System.currentTimeMillis()+60_000L)
                    }else{
                        update(job.id,"VERIFIED",job.taskId,
                            receipt=receipt,attempt=job.attempts)
                        submitted=(submitted-1).coerceAtLeast(0)
                    }
                }catch(e:Exception){
                    val n=job.attempts+1
                    val retry=minorNetwork(e) && n<=5
                    val backoff=if(retry)(15_000L shl (n-1).coerceIn(0,4))
                        else 0L
                    // When submission has possibly succeeded, never send
                    // the same original twice after a network failure. A
                    // submitted task is queried, not re-submitted.
                    if(job.state=="SUBMITTED" && !retry)
                        submitted=(submitted-1).coerceAtLeast(0)
                    update(job.id,if(job.state=="SUBMITTED" && retry)
                            "SUBMITTED" else if(retry)"QUEUED" else "ERROR",
                        job.taskId,error=e.message?:e.javaClass.simpleName,
                        attempt=n,retryAfter=if(retry)
                            System.currentTimeMillis()+backoff else 0L)
                }
            }
            return summary()
        }finally{synchronized(admission){active=false}}
    }
    companion object {
        private val admission=Any()
        @Volatile private var active=false
    }
}
