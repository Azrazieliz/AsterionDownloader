package com.azrael.pixivdumpsync

import android.app.DownloadManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.azrael.galleryflow.SiteSession
import com.azrael.galleryflow.HttpClient
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.thread

/** Android Keystore encrypted retry metadata. Signed URLs never enter plain preferences. */
internal object DirectRetryVault {
    private const val PREF="asterion_direct_retry_private"
    private const val ALIAS="asterion-download-retry-aes-gcm-v1"
    data class Request(val id:Long,val url:String,val folder:String,val name:String,
        val referer:String?,val attempts:Int,val after:Long)
    private fun key():SecretKey{
        val keys=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (keys.getKey(ALIAS,null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
            .apply{init(KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())}.generateKey()
    }
    private fun encrypt(body:String):String{
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key())
        return Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+
            Base64.encodeToString(cipher.doFinal(body.toByteArray()),Base64.NO_WRAP)
    }
    private fun decrypt(value:String):String{
        val parts=value.split(':',limit=2)
        require(parts.size==2)
        val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE,key(),
            GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))
        return String(c.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)))
    }
    fun protect(value:String):String=encrypt(value)
    fun reveal(value:String):String=decrypt(value)
    fun remember(ctx:Context,request:Request){
        val obj=JSONObject().apply{
            put("url",request.url);put("folder",request.folder)
            put("name",request.name);put("referer",request.referer?:"")
            put("attempts",request.attempts);put("after",request.after)
        }
        ctx.getSharedPreferences(PREF,0).edit()
            .putString(request.id.toString(),encrypt(obj.toString())).apply()
    }
    fun remove(ctx:Context,id:Long){
        ctx.getSharedPreferences(PREF,0).edit().remove(id.toString()).apply()
    }
    fun all(ctx:Context):List<Request>{
        val prefs=ctx.getSharedPreferences(PREF,0)
        return prefs.all.mapNotNull{(key,value)->
            val id=key.toLongOrNull()?:return@mapNotNull null
            runCatching{
                val obj=JSONObject(decrypt(value as String))
                Request(id,obj.getString("url"),obj.getString("folder"),
                    obj.getString("name"),obj.optString("referer").takeIf{it.isNotBlank()},
                    obj.optInt("attempts"),obj.optLong("after"))
            }.getOrNull()
        }.sortedByDescending{it.id}.take(250)
    }
}
object DirectDownloadRetry {
    private const val JOB=48764
    private const val STALL_JOB=48765
    // Prevent repeated taps and scheduled recovery from racing to replace
    // the same DownloadManager ID and leaving misleading ghost queue rows.
    private val inFlight=java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val manualScheduled=java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    fun ensure(ctx:Context){
        val jobs=ctx.getSystemService(JobScheduler::class.java)
        if(jobs.allPendingJobs.none{it.id==JOB})
            jobs.schedule(JobInfo.Builder(JOB,ComponentName(ctx,DirectRetryJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true).setPeriodic(15*60_000L).build())
    }
    /** The periodic 15-minute retry check is too slow for a zero-byte
     * request. One OS-scheduled check makes a stalled row retryable without
     * waiting for the next periodic poll or pinning a foreground service.
     */
    private fun scheduleStallCheck(ctx:Context){
        if(!AppPower.enabled(ctx))return
        val scheduler=ctx.getSystemService(JobScheduler::class.java)
        if(scheduler.allPendingJobs.any{it.id==STALL_JOB})return
        val delay=DirectTransferStallPolicy.ZERO_BYTE_TIMEOUT_MS
        scheduler.schedule(JobInfo.Builder(STALL_JOB,
            ComponentName(ctx,DirectRetryJob::class.java))
            .setMinimumLatency(delay)
            .setOverrideDeadline(delay+60_000L)
            .build())
    }
    fun remember(ctx:Context,id:Long,url:String,folder:String,
                 name:String,referer:String?){
        if(id<=0||!SearchRanking.validUrl(url))return
        DirectRetryVault.remember(ctx,DirectRetryVault.Request(id,url,folder,name,referer,0,0))
        scheduleStallCheck(ctx)
    }
    /** Stop a selected OS-owned transfer, not the entire download engine.
     * Release the URL claim and encrypted retry record so the user can
     * intentionally add that original again later. Never delete a saved file.
     */
    fun cancel(ctx:Context,id:Long):Boolean{
        if(manualScheduled.contains(id) || !inFlight.add(id))return false
        return try{
            val current=DirectDownloadTracker.states(ctx,250)
                .firstOrNull{it.job.id==id}?:return false
            if(current.status !in setOf(DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_RUNNING,DownloadManager.STATUS_PAUSED))
                return false
            val manager=ctx.getSystemService(DownloadManager::class.java)
            manager.remove(id)
            CrossSourceDedup(ctx).use{it.releaseById(id)}
            DirectRetryVault.remove(ctx,id)
            DirectDownloadTracker.forget(ctx,id)
            OriginalReceiptState.clear(ctx,id)
            true
        }catch(_:Exception){false}
        finally{inFlight.remove(id)}
    }
    fun retryNow(ctx:Context,id:Long):Boolean{
        if(!AppPower.enabled(ctx))return false
        val record=DirectRetryVault.all(ctx).firstOrNull{it.id==id}?:return false
        val state=DirectDownloadTracker.states(ctx,250)
            .firstOrNull{it.job.id==id}?:return false
        val receipt=OriginalReceiptState.get(ctx,id)
        if(!DirectRetryPolicy.manualRetryable(
                state.status==DownloadManager.STATUS_FAILED,
                state.status==DownloadManager.STATUS_PAUSED,
                state.status==DownloadManager.STATUS_SUCCESSFUL && receipt=="INVALID"
            ))return false
        if(inFlight.contains(id) || !manualScheduled.add(id))return false
        return try{
            DirectRetryVault.remember(ctx,record.copy(attempts=0,after=0))
            thread(name="asterion-manual-direct-retry"){
                try{retrySelected(ctx.applicationContext,id)}
                finally{manualScheduled.remove(id)}
            }
            true
        }catch(_:Exception){
            manualScheduled.remove(id)
            false
        }
    }
    /** DownloadManager broadcasts completion promptly, long before the
     * periodic retry poll. A transport-successful HTML error is not saved.
     */
    fun validateCompleted(ctx:Context,id:Long):Boolean {
        val record=DirectRetryVault.all(ctx).firstOrNull{it.id==id}
            ?:return false
        val manager=ctx.getSystemService(DownloadManager::class.java)
        val done=DirectDownloadTracker.states(ctx,250).firstOrNull{
            it.job.id==id && it.status==DownloadManager.STATUS_SUCCESSFUL
        }?:return false
        val receipt=OriginalFileReceipt.verify(ctx,manager,id,
            record.name,done.total)
        OriginalReceiptState.set(ctx,id,
            if(receipt.valid)"VERIFIED" else "INVALID")
        if(!receipt.valid)return false
        CrossSourceDedup(ctx).use{it.completeById(id)}
        DirectRetryVault.remove(ctx,id)
        return true
    }
    /** Turn a genuine 150-second zero-byte OS transfer into a durable,
     * explicitly retryable failure. Stalled jobs never reserve both module
     * slots indefinitely, and progressing transfers are untouched.
     */
    fun reconcileStalled(ctx:Context):Int {
        if(!AppPower.enabled(ctx))return 0
        val now=System.currentTimeMillis()
        val pending=runCatching{DirectRetryVault.all(ctx).associateBy{it.id}}
            .getOrDefault(emptyMap())
        val manager=ctx.getSystemService(DownloadManager::class.java)
        var stalled=0
        for(state in DirectDownloadTracker.states(ctx,250)){
            if(Thread.currentThread().isInterrupted)break
            if(state.job.id !in pending)continue
            if(!DirectTransferStallPolicy.stalled(
                    state.status==DownloadManager.STATUS_PENDING ||
                    state.status==DownloadManager.STATUS_RUNNING,
                    state.bytes,state.job.created,now))continue
            val original=pending[state.job.id]?:continue
            // Recheck right before cancellation: the OS could have started
            // receiving bytes after the first snapshot. Never remove it then.
            val stillEmpty=runCatching{
                manager.query(DownloadManager.Query().setFilterById(state.job.id))
                    ?.use{cursor->
                        if(!cursor.moveToFirst())false
                        else {
                            val statusColumn=cursor.getColumnIndex(
                                DownloadManager.COLUMN_STATUS)
                            val bytesColumn=cursor.getColumnIndex(
                                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                            statusColumn>=0 && bytesColumn>=0 &&
                                cursor.getInt(statusColumn) in setOf(
                                    DownloadManager.STATUS_PENDING,
                                    DownloadManager.STATUS_RUNNING) &&
                                cursor.getLong(bytesColumn)==0L
                        }
                    }?:false
            }.getOrDefault(false)
            if(!stillEmpty)continue
            // Android returns 0 when the request was already gone; never
            // label a completed/unrelated file stalled in that race.
            if(runCatching{manager.remove(state.job.id)}.getOrDefault(0)<=0)continue
            DirectRetryVault.remember(ctx,original.copy(
                attempts=DirectTransferStallPolicy.TERMINAL_RETRY_ATTEMPTS,
                after=Long.MAX_VALUE))
            OriginalReceiptState.set(ctx,state.job.id,"STALLED")
            stalled++
        }
        return stalled
    }
    fun retry(ctx:Context):Int{
        reconcileStalled(ctx)
        return retrySelected(ctx,null)
    }
    private fun retrySelected(ctx:Context,forceId:Long?):Int{
        if(!AppPower.enabled(ctx))return 0
        val manager=ctx.getSystemService(DownloadManager::class.java)
        val status=DirectDownloadTracker.states(ctx,250).associateBy{it.job.id}
        val now=System.currentTimeMillis()
        var count=0
        for(record in DirectRetryVault.all(ctx)){
            if(Thread.currentThread().isInterrupted)break
            if(forceId!=null && record.id!=forceId)continue
            val state=status[record.id]?:continue
            if(!inFlight.add(record.id))continue
            try {
            val systemSuccess=state.status==DownloadManager.STATUS_SUCCESSFUL
            val receipt=if(systemSuccess)
                OriginalFileReceipt.verify(ctx,manager,record.id,record.name,state.total)
                else null
            if(receipt?.valid==true){
                OriginalReceiptState.set(ctx,record.id,"VERIFIED")
                DirectRetryVault.remove(ctx,record.id)
                CrossSourceDedup(ctx).use{it.completeById(record.id)}
                continue
            }
            val badReceipt=systemSuccess && receipt?.valid==false
            if(badReceipt)OriginalReceiptState.set(ctx,record.id,"INVALID")
            // The OS can report 200 OK for a login page, blocked provider
            // or an HTML error saved as .zip/.epub/.png. Never mark it done.
            // Retry corrupted responses at most twice automatically; account
            // pages require the owner to reconnect the provider manually.
            val loginPage=badReceipt && receipt!!.reason.contains(
                "HTML/XML error or access page")
            val manualResume=forceId==record.id &&
                (state.status==DownloadManager.STATUS_PAUSED ||
                    OriginalReceiptState.get(ctx,record.id)=="STALLED")
            // An OS-removed transfer is never retried on a timer: the
            // downloaded original may have been moved outside the app.
            // Requeue only following the owner's explicit RETRY action.
            val due=if(OriginalReceiptState.get(ctx,record.id)=="MISSING")
                forceId==record.id
                else if(badReceipt)
                forceId==record.id ||
                    (!loginPage && record.attempts<2 && now>=record.after)
                else manualResume || DirectRetryPolicy.shouldAttempt(
                    state.status==DownloadManager.STATUS_FAILED,state.reason,
                    record.attempts,record.after,now,forceId==record.id)
            if(!due)continue
            try{
                val request=DownloadManager.Request(Uri.parse(record.url)).apply{
                    setTitle(record.name)
                    setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                        "AsterionDownloader/"+record.folder+"/"+record.name)
                    addRequestHeader("User-Agent",HttpClient.USER_AGENT)
                    SiteSession.cookie(record.url)?.let{addRequestHeader("Cookie",it)}
                    record.referer?.let{
                        ResourceDownloads.privacyReferer(record.url,it)?.let{header->
                            addRequestHeader("Referer",header)
                        }
                    }
                }
                // Do not destroy the failed transfer until the replacement is queued,
                // tracked, encrypted and bound to its existing deduplication claim.
                val id=manager.enqueue(request)
                val committed=runCatching{
                    DirectDownloadTracker.record(ctx,id,record.name,record.folder,record.url)
                    OriginalReceiptState.set(ctx,id,"UNVERIFIED")
                    DirectRetryVault.remember(ctx,record.copy(id=id,
                        attempts=record.attempts+1,
                        after=now+DirectRetryPolicy.backoffMs(record.attempts)))
                    check(CrossSourceDedup(ctx).use{
                        it.replaceDownloadId(record.url,record.id,id)
                    }){"This original is already claimed by another transfer"}
                }.isSuccess
                if(!committed){
                    manager.remove(id)
                    DirectDownloadTracker.forget(ctx,id)
                    DirectRetryVault.remove(ctx,id)
                    continue
                }
                manager.remove(record.id)
                DirectDownloadTracker.forget(ctx,record.id)
                DirectRetryVault.remove(ctx,record.id)
                OriginalReceiptState.clear(ctx,record.id)
                count++
            }catch(_:Exception){
                // Preserve the prior encrypted record and retry status for investigation.
            }
            } finally {
                inFlight.remove(record.id)
            }
        }
        return count
    }
}
class DirectRetryJob:JobService(){
    private val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
    private val tokens=java.util.concurrent.ConcurrentHashMap<Int,Any>()
    private val tasks=java.util.concurrent.ConcurrentHashMap<Int,
        java.util.concurrent.FutureTask<Unit>>()
    override fun onStartJob(params:JobParameters):Boolean{
        val token=Any()
        val task=java.util.concurrent.FutureTask<Unit> {
            try {
                DirectDownloadRetry.retry(applicationContext)
                Unit
            }finally{
                if(tokens.remove(params.jobId,token)){
                    tasks.remove(params.jobId)
                    if(!Thread.currentThread().isInterrupted)
                        jobFinished(params,false)
                }
            }
        }
        tokens[params.jobId]=token
        tasks.put(params.jobId,task)?.cancel(true)
        executor.execute(task)
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean{
        tokens.remove(params.jobId)
        tasks.remove(params.jobId)?.cancel(true)
        return true
    }
}
