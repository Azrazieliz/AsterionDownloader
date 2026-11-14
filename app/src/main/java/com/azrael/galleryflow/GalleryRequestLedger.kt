package com.azrael.galleryflow

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable owner-selected GalleryFlow requests. A process kill cannot erase
 * albums that the user already tapped while a prior album was transferring.
 * Scheduled untargeted Live scans remain owned by the existing scheduler.
 */
internal object GalleryRequestLedger {
    private const val PREF="asterion_gallery_targeted_jobs_v1"
    private const val KEY="pending"
    private const val MAX_REQUESTS=256
    private val lock=Any()

    data class Request(val mode:SyncMode,val entityId:String?=null,
                       val siteHost:String?=null,
                       val albumUrl:String?=null,
                       val albumTitle:String?=null) {
        val targeted get()=entityId!=null || siteHost!=null || albumUrl!=null
        fun signature():String=mode.name+"|"+entityId.orEmpty()+"|"+
            siteHost.orEmpty()+"|"+albumUrl.orEmpty()
    }

    private fun read(context:Context):MutableList<Request>{
        val raw=context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .getString(KEY,null)?:return mutableListOf()
        return runCatching{
            val list=JSONArray(raw)
            buildList<Request>{
                for(i in 0 until list.length()){
                    val value=list.optJSONObject(i)?:continue
                    val mode=runCatching{SyncMode.valueOf(value.optString("mode"))}
                        .getOrNull()?:continue
                    val entity=value.optString("entity").takeIf{it.isNotBlank()}
                    val host=value.optString("host").takeIf{it.isNotBlank()}
                    val album=value.optString("album").takeIf{it.isNotBlank()}
                    val title=value.optString("title").takeIf{it.isNotBlank()}
                    val req=Request(mode,entity,host,album,title)
                    if(req.targeted && none{it.signature()==req.signature()})
                        add(req)
                }
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun write(context:Context,requests:List<Request>){
        val array=JSONArray()
        requests.forEach{r->
            array.put(JSONObject().put("mode",r.mode.name)
                .put("entity",r.entityId.orEmpty())
                .put("host",r.siteHost.orEmpty())
                .put("album",r.albumUrl.orEmpty())
                .put("title",r.albumTitle.orEmpty()))
        }
        check(context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .edit().putString(KEY,array.toString()).commit()){
            "Failed to persist user-selected gallery requests"
        }
    }

    fun pending(context:Context):List<Request> = synchronized(lock){
        read(context).toList()
    }

    fun remember(context:Context,request:Request)=synchronized(lock){
        if(!request.targeted)return@synchronized
        val all=read(context)
        if(all.none{it.signature()==request.signature()}){
            check(all.size<MAX_REQUESTS){
                "Too many selected albums pending; complete or stop existing jobs first"
            }
            all+=request
            write(context,all)
        }
    }

    fun completed(context:Context,request:Request)=synchronized(lock){
        if(!request.targeted)return@synchronized
        val all=read(context)
        if(all.removeAll{it.signature()==request.signature()})
            write(context,all)
    }

    fun clear(context:Context)=synchronized(lock){write(context,emptyList())}

    /** Restore only explicit unfinished selected albums when the app reopens.
     * A denied foreground-service launch leaves its records intact for retry.
     */
    fun recoverOnAppOpen(context:Context){
        if(!com.azrael.pixivdumpsync.AppPower.enabled(context) ||
            pending(context).isEmpty())return
        runCatching{
            context.startForegroundService(
                Intent(context,SyncForegroundService::class.java)
                    .setAction(SyncForegroundService.ACTION_RECOVER))
        }.onFailure{
            Log.w("GalleryRequestLedger","Selected albums kept for later resume",it)
        }
    }
}
