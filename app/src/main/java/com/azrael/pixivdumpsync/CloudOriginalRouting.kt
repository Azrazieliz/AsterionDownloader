package com.azrael.pixivdumpsync

import android.content.Context

/** Single honest contract for selected public originals.
 *
 * Cloud-only means no Android DownloadManager, MediaStore or hidden local
 * fallback may be used. The remote-import queue owns an encrypted canonical
 * reservation and only credits full receipt after TeraBox confirms the file.
 * Publisher pages, HLS, protected sources and generated archives require a
 * separate cloud-capable resolver; they must not be mislabeled as saved.
 */
internal object CloudOriginalRouting {
    fun category(module:String):String=when(module.uppercase()){
        "VIDEO","VIDEOS","ADULT","HENTAI","PORN"->"videos"
        "COSPLAY","KYORA","GALLERY","GALLERYFLOW"->"kyora"
        "PIXIV","KUROHA"->"kuroha"
        "X"->"x"
        "COMICS","COMIC","MANGA","DOUJIN"->"comics"
        "BOOKS","BOOK","EPUB"->"books"
        "GAMES","GAME","MODS","TORRENTS"->"games"
        "KOIKATSU"->"koikatsu"
        "CREATORS","CREATOR"->"creators"
        "ASSETS","3D","STUDIO","MMD","LIVE2D","ANIMATION"->"assets"
        else->"originals"
    }
    fun enqueue(context:Context,url:String,module:String,
                filename:String?=null):Boolean{
        check(NduSettings.cloudOnly(context)){
            "Cloud original route requires cloud-only download mode"
        }
        check(NduSettings.configured(context)){
            "Connect TeraBox before queuing a cloud original"
        }
        require(TeraBoxRemoteOriginals.verifiedPublicFileLink(url)){
            "This selected source is not a public original HTTPS file URL; "+
                "it needs a cloud-capable source resolver, not a local fallback"
        }
        TeraBoxRemoteQueue(context).use{
            it.enqueue(context,url,category(module),filename)
        }
        // If Android delays JobScheduler, the encrypted durable row remains
        // available to the existing periodic retry and read-after-write pass.
        NduCloudScheduler.requestImmediate(context)
        return true
    }
}
