package com.azrael.pixivdumpsync

import android.content.Context
import com.azrael.galleryflow.GalleryDb
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded creator-linked discovery, not general web crawling or inferred identity.
 *
 * Only previously followed creator profiles and creator-declared link hubs are
 * checked. No sessions or cookies are sent to third-party sites, and redirects
 * must remain on the source's public provider domain.
 */
internal object CreatorLinkDiscovery {
    private const val PREF="asterion_creator_link_discovery_v1"
    private const val DAY=24L*60L*60L*1000L
    private val busy=AtomicBoolean(false)

    internal fun hubLinks(doc:Document,hub:String,origin:String):List<CrossPlatformProfile>{
        val hubIdentity=CrossPlatformProfileParser.normalized(hub)
            ?.takeIf{it.second=="LINK_HUB"}?:return emptyList()
        val original=CrossPlatformProfileParser.normalized(origin)
            ?:return emptyList()
        val result=LinkedHashMap<String,CrossPlatformProfile>()
        for(link in doc.select("a[href]").take(400)){
            if(link.closest("footer,header,nav,aside,.advertisement,.sponsored")!=null)
                continue
            val target=CrossPlatformProfileParser.normalized(link.absUrl("href"))
                ?:continue
            if(target.first==hubIdentity.first || target.first==original.first ||
                target.second==original.second || target.second=="LINK_HUB")continue
            result.putIfAbsent(target.first,CrossPlatformProfile(
                original.first,target.first,target.second,
                "Creator-designated link hub: "+hubIdentity.first))
            if(result.size>=60)break
        }
        return result.values.toList()
    }

    private fun followed(context:Context):List<String>{
        val urls=LinkedHashSet<String>()
        AppDb(context).use{db->
            for(artist in db.listSelectedArtists().take(2500)){
                if(artist.userId.matches(Regex("[0-9]{1,20}")))
                    urls+="https://www.pixiv.net/users/"+artist.userId
            }
        }
        XPhotoQueue(context).use{db->
            for(source in db.allSources().filter{it.enabled}.take(2500))
                urls+=source.url
        }
        GalleryDb(context).use{db->
            for(creator in db.listEntities().filter{it.selected}.take(2500))
                urls+=creator.canonicalUrl
        }
        for(creator in CreatorArchiveStore.list(context).take(2500))
            urls+=creator.url
        SourceIndexDb(context).use{db->
            for(src in db.sources().filter{it.enabled &&
                it.area in setOf("CREATORS","ARTWORK","COSPLAY","ASSETS")}.take(2500))
                urls+=src.url
        }
        return urls.mapNotNull{CrossPlatformProfileParser.normalized(it)?.first}
            .distinct()
    }

    private fun publicPage(address:String):Document{
        val source=URI(address)
        require(source.scheme=="https" &&
            CrossPlatformProfileParser.normalized(address)!=null) {
            "Not an approved creator or link-hub URL"
        }
        var current=address
        repeat(3){
            require(SourceIndexParser.safe(current) &&
                SourceIndexParser.sameSite(address,current)) {
                "Creator profile redirected outside its provider"
            }
            val response=Jsoup.connect(current)
                .timeout(12000).maxBodySize(1_500_000)
                .userAgent("Mozilla/5.0 (Linux; Android 17) AsterionDownloader/1.3")
                .followRedirects(false).ignoreHttpErrors(true)
                .header("Accept","text/html,application/xhtml+xml")
                .execute()
            if(response.statusCode() in 300..399){
                val where=response.header("Location")
                    ?:error("Creator profile redirect missing Location")
                val next=URI(current).resolve(where).toString()
                require(SourceIndexParser.safe(next) &&
                    SourceIndexParser.sameSite(address,next)){
                    "Cross-domain profile redirect refused"
                }
                current=next
            }else{
                require(response.statusCode() in 200..299){
                    "Public creator profile HTTP "+response.statusCode()
                }
                return response.parse()
            }
        }
        error("Creator profile exceeded redirect limit")
    }

    /** Refresh a rotating subset of follows and one-hop link hubs in each
     * scheduled six-hour index scan. Never recursively follow third parties.
     */
    fun scan(context:Context,stop:()->Boolean={false},
             profilesPerRun:Int=5,hubsPerRun:Int=3){
        if(!AppPower.enabled(context) || !busy.compareAndSet(false,true))return
        try{
            val pref=context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            val now=System.currentTimeMillis()
            val profiles=followed(context)
            if(profiles.isNotEmpty()){
                val start=pref.getInt("cursor",0).coerceAtLeast(0)%profiles.size
                var checked=0
                var inspected=0
                while(checked<profiles.size && inspected<profilesPerRun &&
                    !stop() && AppPower.enabled(context)){
                    val address=profiles[(start+checked)%profiles.size]
                    checked++
                    val key="profile_"+address.hashCode()
                    if(now-pref.getLong(key,0)<DAY)continue
                    inspected++
                    val outcome=runCatching{
                        CrossPlatformProfileParser.linkedProfiles(publicPage(address),address)
                    }
                    outcome.onSuccess{records->
                        if(records.isNotEmpty())CrossPlatformCreatorDb(context).use{
                            it.observe(records)
                        }
                        pref.edit().putLong(key,now).remove("error_"+key).apply()
                    }.onFailure{e->
                        // A blocked or temporarily unavailable profile remains
                        // eligible for a future scan rather than being 'complete'.
                        pref.edit().putLong(key,now-(DAY-2L*60L*60_000L))
                            .putString("error_"+key,(e.message?:"Unavailable").take(180))
                            .apply()
                    }
                }
                pref.edit().putInt("cursor",(start+checked)%profiles.size).apply()
            }
            val hubs=CrossPlatformCreatorDb(context).use{it.hubs(2000)}
            var inspectedHubs=0
            for(hub in hubs){
                if(inspectedHubs>=hubsPerRun||stop()||!AppPower.enabled(context))break
                val key="hub_"+hub.url.hashCode()
                if(now-pref.getLong(key,0)<DAY)continue
                inspectedHubs++
                runCatching{hubLinks(publicPage(hub.url),hub.url,hub.source)}
                    .onSuccess{records->
                        if(records.isNotEmpty())CrossPlatformCreatorDb(context).use{
                            it.observe(records)
                        }
                        pref.edit().putLong(key,now).remove("error_"+key).apply()
                    }.onFailure{e->
                        pref.edit().putLong(key,now-(DAY-2L*60L*60_000L))
                            .putString("error_"+key,(e.message?:"Unavailable").take(180))
                            .apply()
                    }
            }
        }finally{busy.set(false)}
    }
}
