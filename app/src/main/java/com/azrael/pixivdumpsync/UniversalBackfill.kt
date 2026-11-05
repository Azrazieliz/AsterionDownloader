package com.azrael.pixivdumpsync

import android.app.Activity
import android.content.Intent
import com.azrael.galleryflow.SyncForegroundService as GalleryService
import com.azrael.galleryflow.SyncMode
import com.asterion.videoflow.VideoEngine

/** Dispatches an explicit all-site backfill request to existing native engines.
 * This does not claim that every third-party provider exposes full history:
 * each engine continues to own its cursors, actual errors and authentication.
 */
object UniversalBackfill {
    data class Result(val requested:Int,val errors:List<String>)
    data class Coverage(val registered:Int,val specialized:Int,
        val checkedGeneric:Int,val awaitingFirstCheck:Int,
        val errors:Int,val disabled:Int)
    fun coverage(a:Activity):Coverage{
        val sources=SourceIndexDb(a).use{it.sources()}
        return Coverage(sources.size,
            sources.count{it.adapter=="native"},
            sources.count{it.adapter=="generic"&&it.lastCheck>0&&
                it.error.isNullOrBlank()},
            sources.count{it.adapter=="generic"&&it.lastCheck==0L},
            sources.count{!it.error.isNullOrBlank()},
            sources.count{!it.enabled})
    }

    fun start(a:Activity):Result{
        val issues=ArrayList<String>()
        var count=0
        fun request(name:String,action:()->Unit){
            try{action();count++}catch(t:Exception){
                issues+="$name: "+(t.message?:t.javaClass.simpleName)
            }
        }
        request("Pixiv artist backfill"){
            check(SessionStore.isLoggedIn(a)){"Pixiv account connection required"}
            a.startForegroundService(Intent(a,SyncForegroundService::class.java)
                .setAction(SyncForegroundService.ACTION_START)
                .putExtra(SyncForegroundService.EXTRA_MODE,SyncMode.BACKFILL.name)
                .putExtra(SyncForegroundService.EXTRA_SELECTED_ONLY,false))
        }
        request("All registered generic sources"){
            SourceIndex.ensure(a)
            SourceIndex.scanNow(a.applicationContext)
        }
        request("OneJAV and Sukebei torrent history"){
            TorrentSourceBackfill.ensure(a)
            TorrentSourceBackfill.scanAsync(a)
        }
        request("MP4Directs series subscriptions"){
            Mp4SeriesSync.ensure(a)
            Mp4SeriesSync.scanNow(a)
        }
        request("Novel EPUB series"){
            NovelEpubService.ensure(a)
            NovelEpubService.schedule(a)
        }
        request("Comic ZIP collections"){ChapterArchives.start(a)}
        request("VideoFlow video sources"){VideoEngine.launch(a,true)}
        request("X followed profiles"){
            XPhotoScheduler.ensure(a)
            XPhotoEngine.start(a)
        }
        request("Creator archives"){
            CreatorArchiveScheduler.ensure(a)
            CreatorArchiveScheduler.scanNow(a)
        }
        request("Kyora image galleries"){
            a.startForegroundService(Intent(a,GalleryService::class.java)
                .setAction(GalleryService.ACTION_START)
                .putExtra(GalleryService.EXTRA_MODE,SyncMode.BACKFILL.name))
        }
        request("Koikatsu cards"){KoikatsuFollowing.ensure(a)}
        return Result(count,issues)
    }
}
