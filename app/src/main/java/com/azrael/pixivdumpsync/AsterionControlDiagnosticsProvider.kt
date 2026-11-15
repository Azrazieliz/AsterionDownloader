package com.azrael.pixivdumpsync

import android.app.Activity
import android.app.ActivityManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import com.asterion.videoflow.VideoDb
import com.asterion.videoflow.VideoTransferJournal
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Read-only opt-in Asterion Control diagnostic adapter.
 *
 * Both sides independently authorize: the physical owner approves Control
 * inside Downloader and also approves Downloader from Control's Integrations.
 * Caller UID, exact package and its current APK signing certificate are checked
 * before EVERY call. No network listener, raw shell, media/URL/cookies/token or
 * private file export. No transfer/queue mutation, action replay or uninstall.
 */
class AsterionControlDiagnosticsProvider:ContentProvider() {
    companion object {
        const val CONTROL="com.asterion.control"
        private const val PREF="asterion_control_diagnostics_owner_v1"
        private const val CERT="approved_control_signer_sha256"
        private const val MAX_RESULT=8192
        private val ACTION=Regex("[a-z][a-z0-9_.-]{0,79}")
        private val REQUEST_ID=Regex("[A-Za-z0-9_.:-]{8,100}")
        private val SUPPORTED=setOf(
            "diagnostics.capabilities",
            "diagnostics.snapshot",
            "diagnostics.crash_summary",
            "diagnostics.video_jobs"
        )
        private fun signer(context:Context):String {
            val pm=context.packageManager
            val bytes=if(Build.VERSION.SDK_INT>=28){
                val pkg=pm.getPackageInfo(CONTROL,PackageManager.GET_SIGNING_CERTIFICATES)
                pkg.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            }else {
                @Suppress("DEPRECATION")
                val pkg=pm.getPackageInfo(CONTROL,PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                pkg.signatures?.firstOrNull()?.toByteArray()
            } ?: throw SecurityException("Control app signing identity unavailable")
            return MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString(""){"%02x".format(it.toInt() and 0xff)}
        }
        fun approved(context:Context):Boolean {
            val expected=context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
                .getString(CERT,"").orEmpty()
            return expected.length==64 &&
                runCatching{expected==signer(context)}.getOrDefault(false)
        }
        /**
         * Only callable from a foreground on-device owner UI.
         * Never expose this method through ContentResolver.call or a receiver.
         */
        fun authorizeLocally(activity:Activity,enable:Boolean):Boolean{
            // The explicit confirmation dialog owns focus while its positive
            // button callback runs, so the Activity decor view is not focused.
            // Require a still-attached real on-device Activity, not window focus.
            if(activity.isFinishing || activity.isDestroyed ||
                !activity.window.decorView.isAttachedToWindow)
                throw SecurityException("Visible owner activity required")
            val fingerprint=if(enable)signer(activity) else ""
            return activity.getSharedPreferences(PREF,Context.MODE_PRIVATE)
                .edit().putString(CERT,fingerprint).commit()
        }
        private fun clean(s:String):String=s.trim().take(64)
            .takeIf{it.matches(Regex("[A-Za-z0-9_.:-]+"))} ?: "redacted"
        private fun bounded(raw:JSONObject):Bundle{
            val text=raw.toString()
            require(text.length<=MAX_RESULT){"Diagnostic result exceeds IPC limit"}
            return Bundle().apply{putString("result_json",text)}
        }
        private fun response(ok:Boolean,status:String,result:JSONObject=JSONObject()):Bundle =
            bounded(JSONObject().put("ok",ok).put("status",status).put("result",result))
    }
    override fun onCreate():Boolean=true
    private fun app():Context=context ?: throw IllegalStateException("App unavailable")
    private fun authorizeCaller(){
        val app=app()
        val uid=Binder.getCallingUid()
        val packages=app.packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
        if(CONTROL !in packages)
            throw SecurityException("Only the installed Asterion Control app may inspect diagnostics")
        if(!approved(app))
            throw SecurityException("Control diagnostic access not locally approved or signing changed")
    }
    private fun capabilities():JSONObject=JSONObject()
        .put("schema_version",1).put("scope","own_downloader_engine")
        .put("read_only",true).put("owner_approved",true)
        .put("global_logcat",false).put("raw_media_export",false)
        .put("download_queue",true).put("video_workers",true)
        .put("verified_transfer_bytes",true).put("crash_exit_summary",true)
        .put("http_per_request_trace",false)
        .put("supported_actions",JSONArray(SUPPORTED.sorted()))

    private fun crashSummary():JSONObject{
        val context=app()
        val result=JSONObject().put("available",Build.VERSION.SDK_INT>=30)
        val exits=JSONArray()
        if(Build.VERSION.SDK_INT>=30){
            val manager=context.getSystemService(ActivityManager::class.java)
            val seen=manager?.getHistoricalProcessExitReasons(context.packageName,0,8)
                .orEmpty()
            for(item in seen){
                exits.put(JSONObject()
                    .put("timestamp_ms",item.timestamp)
                    .put("reason_code",item.reason)
                    .put("status",item.status)
                    .put("process",clean(item.processName.orEmpty()))
                    .put("pss_kb",item.pss)
                    .put("rss_kb",item.rss))
            }
        }
        val saved=java.io.File(context.filesDir,"last_asterion_crash.txt")
        val savedPresent=saved.isFile
        // Existing crash handler keeps the full stack on-device.
        // This read-only bridge exposes only safe existence and class metadata.
        return result.put("exit_reasons",exits).put("private_java_crash_record_exists",savedPresent)
            .put("private_crash_stack_exported",false)
    }

    private fun videoJobs(limit:Int):JSONObject{
        require(limit in 1..30){"Invalid diagnostic worker limit"}
        val context=app()
        val entries=JSONArray()
        val counts=JSONObject()
        var queueCount=0
        var activeCount=0
        var staleCount=0
        val now=System.currentTimeMillis()
        VideoDb(context).use{store->
            val db=store.readableDatabase
            db.rawQuery("SELECT state, COUNT(*) FROM videos GROUP BY state",null).use{cur->
                while(cur.moveToNext()){
                    val state=clean(cur.getString(0).orEmpty())
                    val count=cur.getInt(1)
                    counts.put(state,count)
                    if(state=="queued" || state=="retry_wait")queueCount+=count
                    if(state=="running")activeCount+=count
                }
            }
            db.rawQuery(
                "SELECT id,state,progress,updated,heartbeat_at,lease,attempts,retry_after "+
                "FROM videos WHERE state IN ('queued','retry_wait','running','error',"+
                "'full_only_hold','paused') ORDER BY "+
                "CASE WHEN state='running' THEN 0 ELSE 1 END,updated DESC LIMIT ?",
                arrayOf(limit.toString())).use{cur->
                while(cur.moveToNext()){
                    val id=cur.getLong(0)
                    val state=clean(cur.getString(1).orEmpty())
                    val heartbeat=cur.getLong(4)
                    val stage=VideoTransferJournal.current(context,id)
                    val stale=state=="running" &&
                        (heartbeat<=0L || now-heartbeat>90_000L)
                    if(stale)staleCount++
                    entries.put(JSONObject().put("id",id)
                        .put("state",state)
                        .put("progress_percent",cur.getInt(2))
                        .put("updated_ms",cur.getLong(3))
                        .put("heartbeat_ms",heartbeat)
                        .put("lease",cur.getLong(5))
                        .put("attempts",cur.getInt(6))
                        .put("retry_after_ms",cur.getLong(7))
                        .put("stale_worker",stale)
                        .put("stage",clean(stage?.stage.orEmpty()))
                        .put("verified_bytes",stage?.verifiedBytes?:0L)
                        .put("stage_updated_ms",stage?.updated?:0L))
                }
            }
        }
        return JSONObject().put("counts",counts)
            .put("queued_or_retrying",queueCount)
            .put("running_count",activeCount)
            .put("stale_running_sample_count",staleCount)
            .put("sampled_jobs",entries)
            .put("sample_limit",limit)
            .put("snapshot_utc_ms",now)
            .put("urls_or_titles_exported",false)
    }

    override fun call(method:String, arg:String?, extras:Bundle?):Bundle{
        authorizeCaller()
        val app=app()
        if(method=="asterion.v1.status" || method=="asterion.v1.cancel"){
            val id=extras?.getString("request_id")?:arg.orEmpty()
            require(REQUEST_ID.matches(id)){"Invalid operation ID"}
            val prior=app.getSharedPreferences(PREF,Context.MODE_PRIVATE)
                .getString("receipt:$id",null)
                ?:return response(false,"not_found")
            val status=if(prior=="started")"outcome_unknown" else "completed"
            return response(status=="completed",status,
                JSONObject().put("read_only",true)
                    .put("cancel_supported",false))
        }
        require(method=="asterion.v1.invoke"){"Unsupported bridge method"}
        require(extras?.getInt("protocol_version",-1)==1){"Unsupported protocol"}
        val id=extras.getString("request_id").orEmpty()
        val action=extras.getString("action").orEmpty()
        require(REQUEST_ID.matches(id)&&ACTION.matches(action) && action in SUPPORTED){
            "Unapproved diagnostic action or request identifier"
        }
        val payload=extras.getString("payload_json","{}")
        require(payload.length<=4096){"Diagnostic payload exceeded limit"}
        val input=JSONObject(payload)
        require(input.length()<=3){"Unexpected diagnostic arguments"}
        val prefs=app.getSharedPreferences(PREF,Context.MODE_PRIVATE)
        synchronized(this){
            if(prefs.contains("receipt:$id"))
                return response(false,"duplicate",
                    JSONObject().put("previous_status",
                        prefs.getString("receipt:$id","unknown")))
            check(prefs.edit().putString("receipt:$id","started").commit()){
                "Could not persist diagnostic request receipt"
            }
        }
        return try{
            val data=when(action){
                "diagnostics.capabilities"->capabilities()
                "diagnostics.video_jobs"->videoJobs(input.optInt("limit",12))
                "diagnostics.crash_summary"->crashSummary()
                "diagnostics.snapshot"->JSONObject()
                    .put("capabilities",capabilities())
                    .put("crashes",crashSummary())
                    .put("jobs",videoJobs(input.optInt("limit",10)))
                else->error("Unreachable action")
            }
            val output=response(true,"completed",data)
            prefs.edit().putString("receipt:$id","completed").commit()
            output
        }catch(e:Exception){
            prefs.edit().putString("receipt:$id","failed").commit()
            response(false,"failed",
                JSONObject().put("error_class",clean(e.javaClass.simpleName)))
        }
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,
                       selectionArgs:Array<out String>?,sortOrder:String?):Cursor?{
        throw SecurityException("Only authenticated versioned diagnostics call supported")
    }
    override fun getType(uri:Uri):String?=null
    override fun insert(uri:Uri,values:ContentValues?):Uri?{
        throw SecurityException("Diagnostic adapter is read-only")
    }
    override fun update(uri:Uri,values:ContentValues?,selection:String?,
                        selectionArgs:Array<out String>?):Int{
        throw SecurityException("Diagnostic adapter is read-only")
    }
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int{
        throw SecurityException("Diagnostic adapter is read-only")
    }
}
