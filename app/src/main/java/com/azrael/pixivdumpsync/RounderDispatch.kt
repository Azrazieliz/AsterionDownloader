package com.azrael.pixivdumpsync

import android.app.Activity
import android.app.DownloadManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Environment
import android.provider.BaseColumns
import android.provider.MediaStore
import android.widget.LinearLayout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class RounderState(val queued:Int,val requested:Int,val lastError:String?)
class RounderOutbox(context:Context):
    SQLiteOpenHelper(context,"asterion_rounder_outbox.sqlite",null,1){
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("""CREATE TABLE files(
            content_uri TEXT PRIMARY KEY,name TEXT NOT NULL,category TEXT NOT NULL,
            state TEXT NOT NULL DEFAULT 'PENDING',observed INTEGER NOT NULL,
            last_request INTEGER NOT NULL DEFAULT 0,error TEXT)""")
        db.execSQL("CREATE INDEX rounder_file_status ON files(state,observed)")
    }
    override fun onUpgrade(db:SQLiteDatabase,a:Int,b:Int){}
    @Synchronized fun enqueue(uri:Uri,name:String,category:String){
        if(uri.scheme!="content")return
        val cv=ContentValues().apply{
            put("content_uri",uri.toString());put("name",name.take(180))
            put("category",category.take(50));put("observed",System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("files",null,cv,SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun pending():Int=readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM files WHERE state='PENDING'",null).use{
        if(it.moveToFirst())it.getInt(0) else 0
    }
    fun state():RounderState{
        val db=readableDatabase
        val queued=pending()
        val requested=db.rawQuery("SELECT COUNT(*) FROM files WHERE state='REQUESTED'",null)
            .use{if(it.moveToFirst())it.getInt(0)else 0}
        val error=db.rawQuery("SELECT error FROM files WHERE error IS NOT NULL "+
            "ORDER BY last_request DESC LIMIT 1",null).use{
            if(it.moveToFirst())it.getString(0)else null
        }
        return RounderState(queued,requested,error)
    }
    fun markRequested(){
        val cv=ContentValues().apply{
            put("state","REQUESTED");put("last_request",System.currentTimeMillis())
            putNull("error")
        }
        writableDatabase.update("files",cv,"state='PENDING'",null)
    }
    fun markError(message:String){
        val cv=ContentValues().apply{put("error",message.take(180))}
        writableDatabase.update("files",cv,"state='PENDING'",null)
    }
}
object RounderDispatch {
    private const val JOB=48763
    private val busy=AtomicBoolean(false)
    private const val PREF="asterion_rounder_scanner"
    fun ensure(context:Context){
        val jobs=context.getSystemService(JobScheduler::class.java)
        if(jobs.allPendingJobs.none{it.id==JOB})
            jobs.schedule(JobInfo.Builder(JOB,ComponentName(context,RounderJob::class.java))
                .setPersisted(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15*60_000L).build())
    }
    private fun folderCategory(path:String):String?{
        val p=path.lowercase()
        if(!listOf("asteriondownloader","nocturnepixiv","pixiflow",
            "videoflow","galleryflow","nocturnegallery").any{it in p})return null
        return when{
            "koikatsu" in p->"Koikatsu"
            "assets" in p->"Assets"
            "books" in p->"Books"
            "comics" in p->"Comics"
            "games" in p->"Games"
            "torrent" in p->"Torrents"
            "/x/" in p->"X"
            "videoflow" in p||"videos" in p->"Videos"
            "gallery" in p->"Kyora"
            "pixiv" in p||"pixiflow" in p->"Pixiv"
            else->"Originals"
        }
    }
    /** Only completed, visible files in application-owned source folders are scanned.
     * Scan cursor is (date_added,MediaStore ID), so a large history is not lost at a batch edge.
     */
    fun scan(context:Context):Int{
        if(!busy.compareAndSet(false,true))return 0
        try{
            val prefs=context.getSharedPreferences(PREF,0)
            var stamp=prefs.getLong("stamp",0L)
            var lastId=prefs.getLong("last_id",0L)
            val projection=arrayOf(BaseColumns._ID,MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,MediaStore.MediaColumns.DATE_ADDED)
            var total=0
            val where="("+MediaStore.MediaColumns.DATE_ADDED+">? OR ("+
                MediaStore.MediaColumns.DATE_ADDED+"=? AND "+BaseColumns._ID+">?))"
            val args=arrayOf(stamp.toString(),stamp.toString(),lastId.toString())
            context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,projection,
                where,args,MediaStore.MediaColumns.DATE_ADDED+" ASC, "+
                    BaseColumns._ID+" ASC")?.use{cursor->
                RounderOutbox(context).use{outbox->
                    while(cursor.moveToNext() && total<2500){
                        val id=cursor.getLong(0)
                        val name=cursor.getString(1).orEmpty()
                        val folder=cursor.getString(2).orEmpty()
                        val recorded=cursor.getLong(3)
                        if(recorded!=stamp){stamp=recorded;lastId=0}
                        if(id>lastId)lastId=id
                        val category=folderCategory(folder)?:continue
                        val uri=android.content.ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,id)
                        outbox.enqueue(uri,name,category)
                        total++
                    }
                }
            }
            prefs.edit().putLong("stamp",stamp).putLong("last_id",lastId).apply()
            // Complete exact-URL dedup claims for DownloadManager transfers.
            val manager=context.getSystemService(DownloadManager::class.java)
            CrossSourceDedup(context).use{dedup->
                for(item in DirectDownloadTracker.states(context,200)){
                    if(item.status==DownloadManager.STATUS_SUCCESSFUL)
                        dedup.completeById(item.job.id)
                    if(item.status==DownloadManager.STATUS_FAILED)
                        dedup.releaseById(item.job.id)
                }
            }
            if(RoundSyncBridge.enabled(context)){
                RounderOutbox(context).use{outbox->
                    if(outbox.pending()>0){
                        val result=RoundSyncBridge.trigger(context)
                        if(result.startsWith("Requested Round Sync task"))
                            outbox.markRequested()
                        else outbox.markError(result)
                    }
                }
            }
            return total
        }finally{busy.set(false)}
    }
    fun scanAsync(context:Context){
        thread(name="asterion-rounder-dispatch"){
            runCatching{scan(context.applicationContext)}
        }
    }
    fun render(a:Activity,p:LinearLayout){
        val status=RounderOutbox(a).use{it.state()}
        val ui=NocturneDesign
        val card=ui.card(a,12)
        card.addView(ui.text(a,"TeraBox · Rounder handoff",17f,ui.white,true))
        val configured=RoundSyncBridge.enabled(a) && RoundSyncBridge.taskId(a)>=0
        card.addView(ui.text(a,status.queued.toString()+" awaiting handoff · "+
            status.requested+" sent to Round Sync (unconfirmed) · "+
            if(configured)"Round Sync task configured" else "Round Sync needs configuration",
            12f,ui.secondary),ui.lp(a,6))
        if(!status.lastError.isNullOrBlank())
            card.addView(ui.text(a,status.lastError,12f,ui.danger),ui.lp(a,5))
        card.addView(ui.weighted(a,ui.action(a,"SCAN FILES",false){
            scanAsync(a)
            android.widget.Toast.makeText(a,"Checking owned downloaded files",
                android.widget.Toast.LENGTH_SHORT).show()
        },ui.action(a,"ROUNDER SETTINGS",false){
            RoundSyncBridge.showSettings(a)
        }),ui.lp(a,8))
        p.addView(card,ui.lp(a,10))
    }
}
class RounderJob:JobService(){
    override fun onStartJob(params:JobParameters):Boolean{
        thread(name="asterion-rounder-job"){
            try{RounderDispatch.scan(applicationContext)}
            finally{jobFinished(params,false)}
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean=true
}
