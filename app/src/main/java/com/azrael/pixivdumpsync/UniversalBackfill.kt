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
    fun start(a:Activity):Result{
        val issues=ArrayList<String>()
        var count=0
        fun request(name:String,action:()->Unit){
            try{action();count++}catch(t:Exception){
                issues+="$name: "+(t.message?:t.javaClass.simpleName)
            }
        }
        request("All registered generic sources"){
            SourceIndex.ensure(a)
            SourceIndex.scanNow(a.applicationContext)
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
