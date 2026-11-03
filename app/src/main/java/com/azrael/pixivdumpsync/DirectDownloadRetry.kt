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
        }.take(250)
    }
}
object DirectDownloadRetry {
    private const val JOB=48764
    fun ensure(ctx:Context){
        val jobs=ctx.getSystemService(JobScheduler::class.java)
        if(jobs.allPendingJobs.none{it.id==JOB})
            jobs.schedule(JobInfo.Builder(JOB,ComponentName(ctx,DirectRetryJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true).setPeriodic(15*60_000L).build())
    }
    fun remember(ctx:Context,id:Long,url:String,folder:String,
                 name:String,referer:String?){
        if(id<=0||!SearchRanking.validUrl(url))return
        DirectRetryVault.remember(ctx,DirectRetryVault.Request(id,url,folder,name,referer,0,0))
    }
    fun retry(ctx:Context):Int{
        if(!AppPower.enabled(ctx))return 0
        val manager=ctx.getSystemService(DownloadManager::class.java)
        val status=DirectDownloadTracker.states(ctx,200).associateBy{it.job.id}
        val now=System.currentTimeMillis()
        var count=0
        for(record in DirectRetryVault.all(ctx)){
            val state=status[record.id]?:continue
            if(state.status==DownloadManager.STATUS_SUCCESSFUL){
                DirectRetryVault.remove(ctx,record.id)
                CrossSourceDedup(ctx).use{it.completeById(record.id)}
                continue
            }
            if(state.status!=DownloadManager.STATUS_FAILED || record.attempts>=5 ||
                now<record.after || state.reason in setOf(401,402,403,404,410))continue
            try{
                val request=DownloadManager.Request(Uri.parse(record.url)).apply{
                    setTitle(record.name)
                    setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                        "AsterionDownloader/"+record.folder+"/"+record.name)
                    SiteSession.cookie(record.url)?.let{addRequestHeader("Cookie",it)}
                    record.referer?.let{
                        ResourceDownloads.privacyReferer(record.url,it)?.let{header->
                            addRequestHeader("Referer",header)
                        }
                    }
                }
                manager.remove(record.id)
                CrossSourceDedup(ctx).use{it.releaseById(record.id)}
                val id=manager.enqueue(request)
                CrossSourceDedup(ctx).use{
                    it.reserve(record.url);it.attach(record.url,id)
                }
                DirectDownloadTracker.forget(ctx,record.id)
                DirectDownloadTracker.record(ctx,id,record.name,record.folder,record.url)
                DirectRetryVault.remove(ctx,record.id)
                DirectRetryVault.remember(ctx,record.copy(id=id,attempts=record.attempts+1,
                    after=now+(30_000L shl record.attempts.coerceIn(0,6))))
                count++
            }catch(_:Exception){
                // Preserve the prior encrypted record and retry status for investigation.
            }
        }
        return count
    }
}
class DirectRetryJob:JobService(){
    override fun onStartJob(params:JobParameters):Boolean{
        thread(name="asterion-direct-download-retry"){
            try{DirectDownloadRetry.retry(applicationContext)}
            finally{jobFinished(params,false)}
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean=true
}
