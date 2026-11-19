package com.azrael.pixivdumpsync

import com.asterion.videoflow.VideoSiteProfiles
import com.asterion.videoflow.HentaiHavenEpisodeSource
import com.asterion.videoflow.VideoDb
import com.asterion.videoflow.VideoEngine
import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import kotlin.concurrent.thread
import java.net.URI

/** Only an explicit selected page on an approved provider enters VideoFlow.
 * Index 'kind' is unreliable: a recognized video page may be labeled image.
 * Never submit a provider home/category listing as a video even if indexed
 * kind says video.
 */
internal object SelectedVideoRouter {
    /**
     * One selected-item entry point for catalog, search and public cards.
     * A HentaiHaven series contains multiple separate original episodes;
     * selecting it must show its actual published episodes instead of
     * queuing the series HTML as a failing video.
     */
    fun request(a:Activity,url:String,title:String,area:String):Boolean {
        if(!isSelectedVideo(area,url,"video"))return false
        if(HentaiHavenEpisodeSource.isSeries(url)){
            thread(name="asterion-selected-series-episodes"){
                val result=runCatching {
                    val doc=WebDiscovery.selectedDocument(url,a.applicationContext)
                    HentaiHavenEpisodeSource.episodes(doc,url)
                }
                a.runOnUiThread{
                    if(a.isFinishing || a.isDestroyed)return@runOnUiThread
                    result.onSuccess{episodes->
                        if(episodes.isEmpty()){
                            AlertDialog.Builder(a)
                                .setTitle("No accessible episodes")
                                .setMessage("The selected HentaiHaven series did "+
                                    "not publish an attributable episode list. "+
                                    "No unrelated video has been queued.")
                                .setPositiveButton("Close",null).show()
                        }else{
                            val labels=episodes.mapIndexed{index,episode->
                                "Episode "+(index+1)+" · "+
                                    episode.substringAfterLast('/').take(48)
                            }.toTypedArray()
                            AlertDialog.Builder(a)
                                .setTitle(title.take(90)+" · Select episode")
                                .setItems(labels){_,index->
                                    queueVideo(a,episodes[index],title+" · "+
                                        labels[index].substringBefore(" · "))
                                }
                                .setNegativeButton("Cancel",null).show()
                        }
                    }.onFailure{failure->
                        AlertDialog.Builder(a)
                            .setTitle("Episode list unavailable")
                            .setMessage(failure.message?:
                                "Selected publisher did not return episodes")
                            .setPositiveButton("Close",null).show()
                    }
                }
            }
            return true
        }
        queueVideo(a,url,title)
        return true
    }
    private fun queueVideo(a:Activity,url:String,title:String){
        val accepted=VideoDb(a).use{
            it.addVideo(url,title,origin="user")
        }
        if(accepted)VideoEngine.launch(a.applicationContext,false)
        Toast.makeText(a,if(accepted)
            "Exact video selected · resolving original"
            else "Already tracked in Downloads",
            Toast.LENGTH_SHORT).show()
    }

    fun isSelectedVideo(area:String,url:String,kind:String?=null):Boolean {
        val validArea=area.uppercase() in setOf(
            "ADULT","HENTAI","VIDEOS","VIDEO","PORN")
        if(!SourceIndexParser.safe(url) || !validArea)return false
        // Legacy indexed rows may be labeled adult/hentai rather than video.
        // First-party selected-page recognition is stronger than the old
        // SourceIndex kind, but NEVER overrides a different content module.
        if(VideoSiteProfiles.isVideoPage(url))return true
        if(kind?.lowercase() !in setOf(
                "video","adult","hentai"))return false
        val uri=runCatching{URI(url)}.getOrNull()?:return false
        val path=uri.path.orEmpty().trim('/')
        if(path.isBlank())return false
        val parts=path.lowercase().split('/')
        if(parts.any{it in setOf("category","categories","tag","tags",
                "models","model","actors","actor","search","page","pages",
                "home","latest","popular","trending","privacy","login",
                "register","terms","about","contact","explore",
                "channels","channel","genres","genre","coming-soon",
                "uncensored","engsub","pornstar","pornstars",
                "torrents","downloads","trending-videos")})return false
        return VideoSiteProfiles.coveredHosts.any{known->
            VideoSiteProfiles.sameProvider("https://$known/",url)
        }
    }
}
