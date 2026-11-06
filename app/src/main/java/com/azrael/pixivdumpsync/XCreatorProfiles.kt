package com.azrael.pixivdumpsync

import android.app.Activity
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap

/** Cached per-creator X avatars and latest photo thumbnails.
 * Only public FxEmbed profile/media metadata is retrieved; X downloader remains authoritative.
 */
object XCreatorProfiles {
    data class Profile(val name:String,val avatar:String?,val photos:List<String>,
                       val fetched:Long,val error:String?)
    private const val PREF="asterion_x_creator_profiles_v1"
    private const val DAY=24L*60L*60L*1000L
    private val executor=Executors.newFixedThreadPool(2)
    private val inflight=ConcurrentHashMap.newKeySet<String>()
    private fun key(handle:String)=handle.trim().removePrefix("@").lowercase()
    fun cached(c:Context,handle:String):Profile?=runCatching{
        val s=c.getSharedPreferences(PREF,0).getString(key(handle),null)?:return null
        val o=JSONObject(s)
        val a=o.optJSONArray("photos")
        Profile(o.optString("name").ifBlank{handle},o.optString("avatar").takeIf{it.startsWith("https://")},
            (0 until minOf(6,a?.length()?:0)).mapNotNull{i->
                a?.optString(i)?.takeIf{it.startsWith("https://pbs.twimg.com/media/")}
            },o.optLong("fetched"),o.optString("error").takeIf{it.isNotBlank()})
    }.getOrNull()
    private fun fetch(url:String):JSONObject{
        val response=Jsoup.connect(url).ignoreHttpErrors(true).ignoreContentType(true)
            .userAgent("Mozilla/5.0 (Linux; Android 17) AsterionDownloader/1.3")
            .timeout(18000).maxBodySize(2_000_000).execute()
        require(response.statusCode() in 200..299){"Profile HTTP "+response.statusCode()}
        val data=JSONObject(response.body())
        require(data.optInt("code",200) in 200..299){"Profile not available"}
        return data
    }
    internal fun parseProfile(profile:JSONObject,timeline:JSONObject,handle:String,
                              stamp:Long=System.currentTimeMillis()):Profile {
        val user=profile.optJSONObject("user")?:profile.optJSONObject("profile")
            ?:profile.optJSONObject("author")
        val name=user?.optString("name").orEmpty().ifBlank{"@"+handle}
        val avatar=user?.optString("avatar_url").orEmpty().takeIf{
            it.startsWith("https://") && SearchRanking.validUrl(it)
        }
        val images=LinkedHashSet<String>()
        fun media(post:JSONObject){
            val photos=post.optJSONObject("media")?.optJSONArray("photos")
            for(i in 0 until minOf(photos?.length()?:0,9)){
                val url=photos?.optJSONObject(i)?.optString("url").orEmpty()
                if(url.startsWith("https://pbs.twimg.com/media/"))images+=url
                if(images.size>=5)return
            }
        }
        val timelineItems=timeline.optJSONArray("results")
        for(i in 0 until minOf(timelineItems?.length()?:0,35)){
            timelineItems?.optJSONObject(i)?.let(::media)
            if(images.size>=5)break
        }
        return Profile(name,avatar,images.take(5),stamp,null)
    }
    fun load(a:Activity,handle:String,onReady:(Profile)->Unit){
        val normalized=key(handle)
        if(!normalized.matches(Regex("[a-z0-9_]{1,15}")))return
        cached(a,normalized)?.let{onReady(it)}
        val current=cached(a,normalized)
        if(current!=null && System.currentTimeMillis()-current.fetched<
            if(current.error==null)DAY else 2*60*60_000L)return
        if(!inflight.add(normalized))return
        executor.execute{
            val saved=runCatching{
                val user=fetch("https://api.fxtwitter.com/2/profile/"+
                    URLEncoder.encode(normalized,"UTF-8"))
                val media=runCatching{fetch("https://api.fxtwitter.com/2/profile/"+
                    URLEncoder.encode(normalized,"UTF-8")+"/media?count=30")}
                    .getOrDefault(JSONObject())
                parseProfile(user,media,normalized)
            }.getOrElse{e->
                val previous=cached(a.applicationContext,normalized)
                Profile(previous?.name?:("@"+normalized),previous?.avatar,
                    previous?.photos.orEmpty(),System.currentTimeMillis(),
                    (e.message?:"Metadata unavailable").take(120))
            }
            val json=JSONObject().apply{
                put("name",saved.name);put("avatar",saved.avatar?:"")
                put("photos",JSONArray(saved.photos));put("fetched",saved.fetched)
                put("error",saved.error?:"")
            }
            a.getSharedPreferences(PREF,0).edit().putString(normalized,json.toString()).apply()
            inflight.remove(normalized)
            a.runOnUiThread{
                if(!a.isDestroyed&&!a.isFinishing)onReady(saved)
            }
        }
    }
}
