package com.azrael.pixivdumpsync

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.azrael.galleryflow.HttpClient
import com.azrael.galleryflow.SiteSession
import java.net.URI
import java.util.Locale

/** Saves links actually loaded into the signed-in browser, not protected media
 * that the provider has not made available to the current account.
 */
internal data class VisibleMedia(val url:String,val kind:String,val filename:String="")
internal data class VisibleMediaResult(val queued:Int,val already:Int,val failed:Int)

internal object VisibleMediaSupport {
    private val privateIp=Regex("""^(?:localhost|127(?:\.[0-9]+){3}|10(?:\.[0-9]+){3}|192\.168(?:\.[0-9]+){2}|169\.254(?:\.[0-9]+){2}|0\.0\.0\.0)$""")
    private val extensions=Regex("""(?i)\.(?:jpg|jpeg|png|gif|webp|avif|mp4|m4v|mov|webm|mp3|flac|m4a|wav|ogg|opus|zip|7z|rar|epub|apk|blend|fbx|glb|gltf|stl|pmx|pmd)(?:$|[?#])""")
    fun accepted(page:String,media:VisibleMedia):Boolean=runCatching{
        val from=URI(page)
        val target=URI(media.url)
        val host=target.host?.lowercase(Locale.ROOT).orEmpty()
        from.scheme.equals("https",true) && target.scheme.equals("https",true) &&
            from.host!=null && host.isNotBlank() && target.userInfo==null &&
            !privateIp.matches(host) && !host.endsWith(".local") &&
            !host.endsWith(".internal") && !media.url.contains('\n') &&
            media.kind in setOf("image","video","file") &&
            (media.kind!="file" || extensions.containsMatchIn(media.url) ||
                extensions.containsMatchIn(media.filename))
    }.getOrDefault(false)
    fun fileName(media:VisibleMedia):String{
        val raw=media.filename.takeIf{it.isNotBlank()} ?:
            runCatching{URI(media.url).path.substringAfterLast('/')}.getOrDefault("")
        val safe=raw.replace(Regex("[^A-Za-z0-9._-]"),"_").take(110)
        val ext=when(media.kind){"image"->".jpg";"video"->".mp4";else->".bin"}
        return safe.takeIf{it.isNotBlank() && it.contains('.') &&
            it.substringAfterLast('.').length in 2..8}
            ?: "media_"+media.url.hashCode().toUInt().toString(16)+ext
    }
    internal fun isEpubOriginal(media:VisibleMedia):Boolean=
        media.kind=="file" && fileName(media).endsWith(".epub",true)

    /** A connected browser may show photos and videos in one EXACT album.
     * Kyora owns the photo inventory, but its single album request must not
     * swallow a separately selected video player. VideoFlow still verifies
     * the publisher page and refuses arbitrary CDN/ad media receipts.
     */
    internal fun selectedCosplayVideoPage(page:String,kind:String):Boolean =
        kind=="video" &&
            SiteControls.key(page)=="cosplaytele.com" &&
            com.asterion.videoflow.VideoSiteProfiles.isVideoPage(page)

    fun queue(context:Context,source:String,files:List<VisibleMedia>,
              categoryHint:String?=null):VisibleMediaResult {
        var queued=0;var already=0;var failed=0
        val cosplaySource=AlohaSources.by(AlohaArea.COSPLAY).firstOrNull{
            SiteControls.key(it.url)==SiteControls.key(source)
        }
        val exactCosplayPage=cosplaySource?.let{
            SiteBackfillProfiles.recognizesGalleryPage(it.url,source)
        }==true
        var kyoraScheduled=false
        var cosplayVideoScheduled=false
        for(media in files.distinctBy{it.url}.take(150)){
            if(!accepted(source,media)){failed++;continue}
            // The signed-in browser's Cosplay photo set must produce ONE
            // original Kyora album request, not 150 isolated DownloadManager
            // jobs. Its HTTP 403 errors were never handled by the native
            // gallery parser. When no album is attributable, do not pretend
            // a third-party image URL is a completed original.
            if(cosplaySource!=null){
                if(exactCosplayPage){
                    val albumWasAlreadyQueued=kyoraScheduled
                    if(!kyoraScheduled){
                        val started=runCatching{
                            ApprovedCosplayActions.enqueueExactOriginal(
                                context,cosplaySource.url,source,
                                "Selected Cosplay gallery")
                        }.getOrDefault(false)
                        if(started){queued++;kyoraScheduled=true}
                        else failed++
                    }
                    if(kyoraScheduled &&
                        selectedCosplayVideoPage(source,media.kind)){
                        if(!cosplayVideoScheduled){
                            cosplayVideoScheduled=true
                            val videoResult=runCatching{
                                val added=com.asterion.videoflow.VideoDb(context).use{
                                    it.addVideo(source,"CosplayTele selected article video")
                                }
                                if(added)com.asterion.videoflow.Scheduling.requestRetry(
                                    context,90_000L)
                                added
                            }
                            videoResult.onSuccess{added->
                                if(added)queued++ else already++
                            }.onFailure{failed++}
                        }else already++
                    }else if(albumWasAlreadyQueued)already++
                    // Keep the exact photo album in Kyora. VideoFlow receives
                    // the selected publisher ARTICLE, never arbitrary media
                    // captured from a related iframe or advertisement.
                    continue
                }
                if(media.kind=="image"){
                    failed++ // Select the source's actual album first.
                    continue
                }
            }
            // EPUB forum/novel shelves should not silently download video,
            // cover images or HTML as a book. Other modules retain their routes.
            if(categoryHint=="Books" && !isEpubOriginal(media)){
                failed++;continue
            }
            val folder=when{
                categoryHint=="Books"->"Books"
                media.kind=="video"->"Videos"
                media.kind=="image"->"Creators"
                else->"Files"
            }
            // The native video pipeline continues to enforce its own full-file
            // validation. Other accessible originals use the existing dedup queue.
            if(media.kind=="video"){
                val ok=runCatching{
                    com.asterion.videoflow.VideoDb(context).use{
                        it.addVideo(media.url,fileName(media))
                    }
                }.getOrDefault(false)
                if(ok){queued++
                    runCatching{com.asterion.videoflow.VideoEngine.launch(context,false)}
                } else already++
                continue
            }
            if(NduSettings.cloudOnly(context)){
                val ok=runCatching{
                    CloudOriginalRouting.enqueue(
                        context,media.url,folder,fileName(media))
                }.isSuccess
                if(ok)queued++ else failed++
                continue
            }
            if(!CrossSourceDedup(context).use{it.reserve(media.url)}){already++;continue}
            try{
                val filename=fileName(media)
                val req=DownloadManager.Request(Uri.parse(media.url)).apply{
                    setTitle(filename)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                        "AsterionDownloader/$folder/$filename")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    addRequestHeader("User-Agent",HttpClient.USER_AGENT)
                    SiteSession.cookie(media.url)?.let{addRequestHeader("Cookie",it)}
                    ResourceDownloads.privacyReferer(media.url,source)?.let{
                        addRequestHeader("Referer",it)
                    }
                }
                val id=context.getSystemService(DownloadManager::class.java).enqueue(req)
                CrossSourceDedup(context).use{it.attach(media.url,id)}
                DirectDownloadTracker.record(context,id,filename,folder,media.url)
                DirectDownloadRetry.remember(context,id,media.url,folder,filename,source)
                queued++
            }catch(t:Exception){
                CrossSourceDedup(context).use{it.release(media.url)}
                failed++
            }
        }
        return VisibleMediaResult(queued,already,failed)
    }
}
