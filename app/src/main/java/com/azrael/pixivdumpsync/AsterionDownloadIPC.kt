package com.azrael.pixivdumpsync

import android.app.DownloadManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.azrael.galleryflow.SiteSession
import com.asterion.videoflow.VideoDb
import com.asterion.videoflow.VideoEngine
import java.security.MessageDigest
import kotlin.concurrent.thread

/** Signature-protected IPC protocol for other signed Asterion applications.
 * ACTION_REQUEST extras: url (HTTPS), category, name (optional).
 * ACTION_RESULT extras: request_id, state, message; states QUEUED/NEEDS_AUTH/ERROR.
 * No sensitive URL is broadcast, written to logs, or stored in plain database fields.
 */
object AsterionDownloadIPC {
    const val ACTION_REQUEST="com.azrael.pixivdumpsync.action.DOWNLOAD_REQUEST"
    const val ACTION_RESULT="com.azrael.pixivdumpsync.action.DOWNLOAD_RESULT"
    const val EXTRA_URL="url"
    const val EXTRA_CATEGORY="category"
    const val EXTRA_NAME="name"
    const val EXTRA_REQUEST_ID="request_id"
    const val EXTRA_STATE="state"
    const val PERMISSION="com.azrael.pixivdumpsync.permission.REQUEST_ASTERION_DOWNLOAD"
    val categories=setOf("ASSETS","ARTWORK","VIDEOS","BOOKS","COMICS","GAMES","KOIKATSU")
    private fun hex(value:ByteArray)=value.joinToString(""){"%02x".format(it)}
    fun identifier(url:String,category:String):String=hex(
        MessageDigest.getInstance("SHA-256")
            .digest((category+"|"+SearchRanking.canonicalKey(url))
                .toByteArray(Charsets.UTF_8)))
}
data class InterAppRequest(val id:String,val url:String,val category:String,
    val name:String,val sender:String,val attempts:Int)
class InterAppDownloadDb(context:Context):
    SQLiteOpenHelper(context,"asterion_interapp_downloads.sqlite",null,1){
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("""CREATE TABLE requests(
            id TEXT PRIMARY KEY,secret TEXT NOT NULL,
            category TEXT NOT NULL,name TEXT NOT NULL,sender TEXT NOT NULL,
            state TEXT NOT NULL DEFAULT 'PENDING',attempts INTEGER NOT NULL DEFAULT 0,
            retry_after INTEGER NOT NULL DEFAULT 0,last_update INTEGER NOT NULL,
            message TEXT)""")
    }
    override fun onUpgrade(db:SQLiteDatabase,a:Int,b:Int){}
    @Synchronized fun enqueue(url:String,category:String,name:String,sender:String,
                              approved:Boolean=true):String{
        require(AsterionDownloadIPC.categories.contains(category)){"Unknown content category"}
        require(url.startsWith("https://") && SearchRanking.validUrl(url)){
            "Only verified HTTPS originals or creator pages are accepted"
        }
        val id=AsterionDownloadIPC.identifier(url,category)
        val cv=ContentValues().apply{
            put("id",id);put("secret",DirectRetryVault.protect(url))
            put("category",category);put("name",name.take(140))
            put("sender",sender.take(120));put("last_update",System.currentTimeMillis())
            put("state",if(approved)"PENDING" else "WAITING_APPROVAL")
        }
        writableDatabase.insertWithOnConflict("requests",null,cv,
            SQLiteDatabase.CONFLICT_IGNORE)
        return id
    }
    fun approve(pkg:String){
        writableDatabase.execSQL(
            "UPDATE requests SET state='PENDING' WHERE sender=? AND state='WAITING_APPROVAL'",
            arrayOf(pkg))
    }
    fun freeze(pkg:String){
        writableDatabase.execSQL(
            "UPDATE requests SET state='WAITING_APPROVAL' "+
                "WHERE sender=? AND state IN ('PENDING','RETRY')",arrayOf(pkg))
    }
    fun pending(limit:Int=4):List<InterAppRequest>{
        val out=ArrayList<InterAppRequest>()
        readableDatabase.rawQuery("""SELECT id,secret,category,name,sender,attempts
            FROM requests WHERE state IN ('PENDING','RETRY') AND retry_after<=?
            ORDER BY last_update ASC LIMIT ?""",
            arrayOf(System.currentTimeMillis().toString(),limit.coerceIn(1,10).toString())
        ).use{cur->
            while(cur.moveToNext()){
                val url=runCatching{DirectRetryVault.reveal(cur.getString(1))}
                    .getOrNull()?:continue
                out+=InterAppRequest(cur.getString(0),url,cur.getString(2),
                    cur.getString(3),cur.getString(4),cur.getInt(5))
            }
        }
        return out
    }
    fun status(id:String,state:String,message:String?=null,attempts:Int=0){
        val cv=ContentValues().apply{
            put("state",state);put("last_update",System.currentTimeMillis())
            put("attempts",attempts)
            if(message==null)putNull("message") else put("message",message.take(170))
            if(state=="RETRY")put("retry_after",System.currentTimeMillis()+
                (30_000L shl attempts.coerceIn(0,7)).coerceAtMost(6*60*60_000L))
        }
        writableDatabase.update("requests",cv,"id=?",arrayOf(id))
    }
    fun summary():Pair<Int,Int>{
        val counts=HashMap<String,Int>()
        readableDatabase.rawQuery("SELECT state,COUNT(*) FROM requests GROUP BY state",null)
            .use{c->while(c.moveToNext())counts[c.getString(0)]=c.getInt(1)}
        return (counts.getOrDefault("PENDING",0)+counts.getOrDefault("RETRY",0)) to
            counts.getOrDefault("NEEDS_AUTH",0)
    }
}
class AsterionDownloadRequestReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        if(intent.action!=AsterionDownloadIPC.ACTION_REQUEST)return
        // Capture the system UID before returning from onReceive; no caller-supplied
        // extras are trusted as identity. Older Android releases must use the
        // existing explicit user-mediated import instead of unverified broadcasts.
        val uid=if(Build.VERSION.SDK_INT>=34)sentFromUid else -1
        val pending=goAsync()
        thread(name="asterion-interapp-intake"){
            try{
                val url=intent.getStringExtra(AsterionDownloadIPC.EXTRA_URL).orEmpty()
                val category=intent.getStringExtra(AsterionDownloadIPC.EXTRA_CATEGORY)
                    .orEmpty().uppercase()
                val name=intent.getStringExtra(AsterionDownloadIPC.EXTRA_NAME)
                    .orEmpty()
                val peer=AsterionPeerTrust.verifiedCaller(context,uid)
                    ?:error("Sender was not a verified Asterion app")
                val id=InterAppDownloadDb(context).use{
                    it.enqueue(url,category,name,peer.pkg,peer.approved)
                }
                if(peer.approved)AsterionDownloadWorker.schedule(context)
                if(isOrderedBroadcast){
                    setResultCode(android.app.Activity.RESULT_OK)
                    setResultData(id)
                }
            }catch(_:Exception){
                if(isOrderedBroadcast)setResultCode(android.app.Activity.RESULT_CANCELED)
            }finally{pending.finish()}
        }
    }
}
object AsterionDownloadWorker {
    private const val PERIODIC=49867
    private const val ONCE=49868
    fun ensure(context:Context){
        val service=context.getSystemService(JobScheduler::class.java)
        if(service.allPendingJobs.none{it.id==PERIODIC})
            service.schedule(JobInfo.Builder(PERIODIC,
                ComponentName(context,AsterionDownloadJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true).setPeriodic(15*60_000L).build())
    }
    fun schedule(context:Context){
        context.getSystemService(JobScheduler::class.java)
            .schedule(JobInfo.Builder(ONCE,
                ComponentName(context,AsterionDownloadJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(1000).setOverrideDeadline(60_000L).build())
    }
    fun run(context:Context){
        if(!AppPower.enabled(context))return
        InterAppDownloadDb(context).use{db->
            for(item in db.pending()){
                try{
                    val hint=when(item.category){
                        "ASSETS"->"ASSETS"
                        "KOIKATSU"->"ARTWORK"
                        "ARTWORK"->"ARTWORK"
                        "VIDEOS"->"VIDEOS"
                        "BOOKS"->"BOOKS"
                        "COMICS"->"COMICS"
                        else->"GAMES"
                    }
                    val original=AuthorizedOriginalResolver.resolve(item.url,hint)
                    if(item.category=="KOIKATSU" &&
                        !original.filename.endsWith(".png",true))
                        error("A Koikatsu character card must be an original PNG")
                    if(item.category=="BOOKS" && original.kind!="epub")
                        error("Books require an original EPUB")
                    if(item.category=="VIDEOS"){
                        val queued=VideoDb(context).use{
                            it.addVideo(original.url,item.name.ifBlank{original.filename})
                        }
                        if(queued)runCatching{VideoEngine.launch(context,false)}
                    }else{
                        if(!CrossSourceDedup(context).use{it.reserve(original.url)}){
                            db.status(item.id,"QUEUED","Original already in download history")
                            continue
                        }
                        val filename=original.filename.take(95)
                        val folder=when(item.category){
                            "KOIKATSU"->"Koikatsu/Cards"
                            "ASSETS"->"Assets";"ARTWORK"->"Images"
                            "BOOKS"->"Books";"COMICS"->"Comics"
                            else->"Games"
                        }
                        val dm=context.getSystemService(DownloadManager::class.java)
                        val request=DownloadManager.Request(Uri.parse(original.url)).apply{
                            setTitle(filename)
                            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                                "AsterionDownloader/"+folder+"/"+filename)
                            setNotificationVisibility(
                                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            SiteSession.cookie(original.url)?.let{
                                addRequestHeader("Cookie",it)
                            }
                            ResourceDownloads.privacyReferer(original.url,original.referer)
                                ?.let{addRequestHeader("Referer",it)}
                        }
                        val id=dm.enqueue(request)
                        CrossSourceDedup(context).use{it.attach(original.url,id)}
                        DirectDownloadTracker.record(context,id,filename,folder,original.url)
                        DirectDownloadRetry.remember(context,id,original.url,folder,filename,
                            original.referer)
                    }
                    db.status(item.id,"QUEUED","Accepted by native download engine")
                    context.sendBroadcast(Intent(AsterionDownloadIPC.ACTION_RESULT).apply{
                        setPackage(item.sender)
                        putExtra(AsterionDownloadIPC.EXTRA_REQUEST_ID,item.id)
                        putExtra(AsterionDownloadIPC.EXTRA_STATE,"QUEUED")
                    })
                }catch(t:Exception){
                    val auth=(t as? OriginalAccessException)?.reason in
                        setOf(OriginalAccessCause.ENTITLEMENT,OriginalAccessCause.SIGN_IN)
                    db.status(item.id,if(auth)"NEEDS_AUTH"
                        else if(item.attempts>=5)"ERROR" else "RETRY",
                        t.message?.take(150),item.attempts+1)
                }
            }
        }
    }
}
class AsterionDownloadJob:JobService(){
    override fun onStartJob(params:JobParameters):Boolean{
        thread(name="asterion-interapp-download-job"){
            try{AsterionDownloadWorker.run(applicationContext)}
            finally{jobFinished(params,false)}
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean=true
}
