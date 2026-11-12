package com.azrael.pixivdumpsync

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/** Integrates Android DownloadManager jobs into Asterion's native Downloads tab. */
object DirectDownloadTracker {
    private const val PREF="asterion_direct_downloads"
    private const val KEY="jobs"
    data class Tracked(val id:Long,val filename:String,val folder:String,
                       val sourceHost:String,val created:Long)
    data class State(val job:Tracked,val status:Int,val bytes:Long,
                     val total:Long,val reason:Int,val localUri:String?)
    private fun prefs(c:Context)=c.getSharedPreferences(PREF,0)
    @Synchronized internal fun read(c:Context):List<Tracked> = runCatching{
        val a=JSONArray(prefs(c).getString(KEY,"[]"))
        (0 until a.length()).mapNotNull{i->
            val o=a.optJSONObject(i)?:return@mapNotNull null
            Tracked(o.optLong("id"),o.optString("filename"),o.optString("folder"),
                o.optString("host"),o.optLong("created"))
        }.filter{it.id>0}
    }.getOrDefault(emptyList())
    @Synchronized fun record(c:Context,id:Long,filename:String,folder:String,url:String){
        if(id<=0)return
        val host=runCatching{Uri.parse(url).host.orEmpty()}.getOrDefault("")
        val saved=read(c).filter{it.id!=id}.toMutableList()
        saved.add(0,Tracked(id,filename.take(100),folder.take(30),host.take(80),
            System.currentTimeMillis()))
        val arr=JSONArray()
        for(job in saved.take(220))arr.put(JSONObject().apply{
            put("id",job.id);put("filename",job.filename)
            put("folder",job.folder);put("host",job.sourceHost)
            put("created",job.created)
        })
        prefs(c).edit().putString(KEY,arr.toString()).apply()
    }
    @Synchronized fun forget(c:Context,id:Long){
        val saved=read(c).filter{it.id!=id}
        val arr=JSONArray()
        for(job in saved)arr.put(JSONObject().apply{
            put("id",job.id);put("filename",job.filename)
            put("folder",job.folder);put("host",job.sourceHost)
            put("created",job.created)
        })
        prefs(c).edit().putString(KEY,arr.toString()).apply()
    }
    fun states(c:Context,limit:Int=50):List<State>{
        val saved=read(c).take(limit.coerceIn(1,250))
        if(saved.isEmpty())return emptyList()
        val manager=c.getSystemService(DownloadManager::class.java)
        val byId=HashMap<Long,State>()
        val query=DownloadManager.Query().setFilterById(*saved.map{it.id}.toLongArray())
        manager.query(query)?.use{cursor->
            fun long(name:String):Long{
                val i=cursor.getColumnIndex(name)
                return if(i>=0&&!cursor.isNull(i))cursor.getLong(i) else 0L
            }
            fun str(name:String):String?{
                val i=cursor.getColumnIndex(name)
                return if(i>=0&&!cursor.isNull(i))cursor.getString(i) else null
            }
            val lookup=saved.associateBy{it.id}
            while(cursor.moveToNext()){
                val id=long(DownloadManager.COLUMN_ID)
                val job=lookup[id]?:continue
                byId[id]=State(job,long(DownloadManager.COLUMN_STATUS).toInt(),
                    long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                    long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                    long(DownloadManager.COLUMN_REASON).toInt(),
                    str(DownloadManager.COLUMN_LOCAL_URI))
            }
        }
        // The OS request was deliberately stopped after a verified
        // zero-byte timeout. Keep the original in the native Downloads list,
        // as a FAILED row rather than an invisible, forever-queued entry.
        // Its encrypted metadata and dedup claim survive for manual retry.
        val now=System.currentTimeMillis()
        return saved.map{job->
            byId[job.id] ?: run {
                val previous=OriginalReceiptState.get(c,job.id)
                // The system provider may need a moment to expose a newly
                // enqueued request. After that, an absent OS row must remain
                // visible and manually recoverable, not vanish from Downloads.
                val newlyEnqueued=job.created>0L && now>=job.created &&
                    now-job.created<60_000L
                if(newlyEnqueued && previous!="STALLED" && previous!="MISSING")
                    State(job,DownloadManager.STATUS_PENDING,0L,-1L,0,null)
                else {
                    if(previous!="STALLED" && previous!="MISSING")
                        OriginalReceiptState.set(c,job.id,"MISSING")
                    State(job,DownloadManager.STATUS_FAILED,0L,-1L,
                        DownloadManager.ERROR_UNKNOWN,null)
                }
            }
        }
    }
    fun render(a:Activity,body:LinearLayout){
        val data=states(a,35)
        if(data.isEmpty())return
        val ui=NocturneDesign
        body.addView(ui.text(a,"Direct downloads",18f,ui.white,true),ui.lp(a,18))
        val active=data.count{it.status==DownloadManager.STATUS_RUNNING ||
            it.status==DownloadManager.STATUS_PENDING}
        body.addView(ui.text(a,active.toString()+" active · "+data.size+
            " recent · Books / Assets / Originals",12f,ui.secondary),ui.lp(a,4))
        for(item in data.take(20)){
            val card=ui.card(a,12)
            card.addView(ui.text(a,item.job.filename,14f,ui.white,true))
            val progress=if(item.total>0)
                " · "+(100L*item.bytes/item.total).toInt().coerceIn(0,100)+"%"
                else ""
            val receipt=OriginalReceiptState.get(a,item.job.id)
            val label=when(item.status){
                DownloadManager.STATUS_SUCCESSFUL->when(receipt){
                    "VERIFIED"->"Saved original"
                    "INVALID"->"Invalid file · check source and retry"
                    else->"Transfer finished · verifying original"
                }
                DownloadManager.STATUS_RUNNING->"Downloading"+progress
                DownloadManager.STATUS_PENDING->"Queued"
                DownloadManager.STATUS_PAUSED->"Paused"+progress
                DownloadManager.STATUS_FAILED->"Failed · system reason "+item.reason
                else->"Waiting"
            }
            card.addView(ui.text(a,item.job.folder+" · "+item.job.sourceHost+
                " · "+label,12f,if(item.status==DownloadManager.STATUS_FAILED ||
                    receipt=="INVALID") ui.danger else ui.secondary),ui.lp(a,5))
            if(item.status==DownloadManager.STATUS_SUCCESSFUL){
                card.addView(ui.action(a,"OPEN FILE",false){
                    val manager=a.getSystemService(DownloadManager::class.java)
                    val checked=OriginalFileReceipt.verify(
                        a,manager,item.job.id,item.job.filename,item.total)
                    OriginalReceiptState.set(a,item.job.id,
                        if(checked.valid)"VERIFIED" else "INVALID")
                    val file=if(checked.valid)
                        manager.getUriForDownloadedFile(item.job.id) else null
                    if(file==null)Toast.makeText(a,
                        if(checked.valid)"Downloaded file is unavailable"
                        else "Original rejected: "+checked.reason,
                        Toast.LENGTH_LONG).show()
                    else runCatching{
                        a.startActivity(Intent(Intent.ACTION_VIEW).apply{
                            setDataAndType(file,a.contentResolver.getType(file)?:"*/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    }.onFailure{
                        Toast.makeText(a,"No suitable file viewer installed",
                            Toast.LENGTH_LONG).show()
                    }
                },ui.lp(a,7))
            }
            body.addView(card,ui.lp(a,7))
        }
    }
}
