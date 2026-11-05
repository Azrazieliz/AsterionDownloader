package com.azrael.pixivdumpsync

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.azrael.galleryflow.SiteSession
import org.jsoup.Jsoup
import java.net.URI
import java.net.URL
import java.net.HttpURLConnection
import java.io.File
import java.security.MessageDigest
import kotlin.concurrent.thread

/** Actual HTTP downloading, safely resolved .torrent files, and Android torrent-client handoff.
 * The torrent transfer itself is performed by a compatible installed BitTorrent client.
 */
object ResourceDownloads {
    private fun note(c:Context,message:String)=
        Toast.makeText(c,message,Toast.LENGTH_LONG).show()

    internal fun privacyReferer(download:String,origin:String):String?=runCatching{
        if(!SearchRanking.validUrl(download)||!SearchRanking.validUrl(origin))return null
        val d=URI(download)
        val s=URI(origin)
        if(d.scheme!="https"||s.scheme!="https")return null
        val path=if(d.host.equals(s.host,true))s.path.orEmpty().ifBlank{"/"} else "/"
        URI(s.scheme,null,s.host,s.port,path,null,null).toString()
    }.getOrNull()

    private fun action(a:Activity,title:String,doIt:()->Unit):TextView=
        NocturneDesign.action(a,title,false,doIt)

    fun render(a:Activity,body:LinearLayout,games:Boolean){
        val ui=NocturneDesign
        val title=if(games)"Games & Visual Novels" else "Books & Novels"
        val folder=if(games)"Games" else "Books"
        body.addView(ui.text(a,title,23f,ui.white,true),ui.lp(a,18))
        body.addView(ui.text(a,if(games)
            "Search and download game torrents; choose all required game assets and play through a compatible Android runtime."
            else "Books use EPUB originals only. Comic chapter ZIPs belong in Toonily / MangaRead.",
            13f,ui.secondary),ui.lp(a,9))
        CatalogSearchUi.render(a,body,games)
        AlohaSources.render(a,body,if(games) listOf(AlohaArea.GAMES,AlohaArea.REFERENCE)
            else listOf(AlohaArea.BOOKS),"BOOKMARKED "+(if(games)"GAMES & DATABASES" else "NOVEL LIBRARIES"))
        CreatorArchiveUi.render(a,body,if(games)ArchiveSection.GAMES else ArchiveSection.BOOKS)
        if(!games){
            TorrentSearchUi.render(a,body,TorrentArea.BOOKS)
            val libraries=ui.card(a)
            libraries.addView(ui.text(a,"BOOK LIBRARIES · EPUB ONLY",16f,ui.white,true),ui.lp(a,3))
            libraries.addView(ui.text(a,"Standard Ebooks and Wikisource are searchable above with direct EPUB downloads. ManyBooks and Feedbooks have browseable libraries; only verified accessible EPUBs can be downloaded.",12f,ui.secondary),ui.lp(a,8))
            libraries.addView(action(a,"BROWSE MANYBOOKS LIBRARY"){
                a.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://manybooks.net/search-book")))
            },ui.lp(a,6))
            libraries.addView(action(a,"BROWSE FEEDBOOKS PUBLIC DOMAIN"){
                a.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.feedbooks.com/publicdomain")))
            },ui.lp(a,6))
            libraries.addView(action(a,"BROWSE LIGHT NOVEL LIBRARY"){
                a.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://mp4directs.com/")))
            },ui.lp(a,6))
            body.addView(libraries,ui.lp(a,11))
        }

        val torrent=ui.card(a)
        torrent.addView(ui.text(a,"BITTORRENT",15f,ui.white,true),ui.lp(a,3))
        torrent.addView(ui.text(a,
            "Game torrents: search by TITLE above, select a torrent result, choose the complete game files, then monitor verified peer "+
            "transfers below. Completed files are exported to Downloads/AsterionDownloader/Games/Torrents; run only with a compatible external Android app.",
            12f,ui.secondary),ui.lp(a,7))
        torrent.addView(action(a,"ACTIVE / COMPLETED TORRENTS · PAUSE / RESUME"){
            TorrentTransfers.showQueue(a)
        },ui.lp(a,10))
        if(games)body.addView(torrent,ui.lp(a,14))
        val card=ui.card(a)
        card.addView(ui.text(a,"ADVANCED DIRECT LINK",13f,ui.secondary,true),ui.lp(a,6))
        val field=EditText(a).apply{
            hint="Paste optional direct download URL"
            setSingleLine(true)
            textSize=14f
            setTextColor(ui.white)
            setHintTextColor(ui.subdued)
            background=ui.shape(a,ui.raised,11)
            setPadding(ui.px(a,12),ui.px(a,8),ui.px(a,12),ui.px(a,8))
        }
        card.addView(field,ui.lp(a,10))
        card.addView(ui.action(a,"DOWNLOAD / OPEN LINK",true) {
            val raw=field.text.toString().trim()
            route(a,raw,folder)
        },ui.lp(a,10))
        if(games)card.addView(action(a,"OPEN TORRENT LISTING"){
            val raw=field.text.toString().trim()
            if(!SearchRanking.validUrl(raw)) {
                note(a,"Paste the full HTTPS torrent listing URL")
            }else openTorrentSource(a,raw)
        },ui.lp(a,6))
        body.addView(card,ui.lp(a,14))
        val files=ui.card(a)
        files.addView(ui.text(a,"LOCAL FILES",15f,ui.white,true),ui.lp(a,3))
        files.addView(action(a,"OPEN EXISTING ARCHIVE / GAME"){
            val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type="*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            a.startActivityForResult(intent,if(games)5202 else 5201)
        },ui.lp(a,8))
        if(games){
            files.addView(ui.text(a,"ANDROID VN COMPATIBILITY",15f,ui.white,true),ui.lp(a,12))
            files.addView(ui.text(a,
                "Export ZIP game assets to Downloads/AsterionDownloader/Games for independent Android runtimes. "+
                "JoiPlay supports many Ren'Py and HTML5 games with its plugins. "+
                "Native Android APKs install separately. Ren'Py/RAPT builds actual standalone Android packages; Windows-only EXEs cannot all be converted.",
                12f,ui.secondary),ui.lp(a,7))
            files.addView(action(a,"IDENTIFY GAME & PREPARE ANDROID RUNTIME"){
                val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type="application/zip"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                a.startActivityForResult(intent,5203)
            },ui.lp(a,10))
            files.addView(action(a,"GET EXTERNAL ANDROID VN PLAYER"){
                a.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.joiplay.net/")))
            },ui.lp(a,7))
            files.addView(action(a,"REN'PY STANDALONE APK PACKAGING"){
                a.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.renpy.org/doc/html/android.html")))
            },ui.lp(a,7))
        }
        body.addView(files,ui.lp(a,14))

    }

    fun onFilePicked(a:Activity,request:Int,uri:Uri?){
        if(uri==null)return
        val name=runCatching{a.contentResolver.query(uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{
                if(it.moveToFirst())it.getString(0) else null
            }}.getOrNull().orEmpty()
        if(request==5202 || request==5203){
            // The existing per-engine planner now handles both routes:
            // native APK when possible, compatible Android runtime otherwise.
            GamePortPlanner.inspect(a,uri,name.ifBlank{
                uri.lastPathSegment.orEmpty()
            })
            return
        }
        val lower=name.lowercase()
        val engine=when{
            lower.endsWith(".apk")->"Native Android package: open with the Android installer."
            lower.endsWith(".html")||lower.endsWith(".htm")->
                "HTML5 game: import its complete ZIP to run offline."
            lower.endsWith(".rpa")||lower.endsWith(".rpy")->
                "Ren'Py project: Android RAPT build required."
            lower.endsWith(".xp3")->"Kirikiri: compatible engine runtime required."
            lower.endsWith(".nsa")||lower.endsWith(".ns2")->
                "NScripter: compatible engine runtime required."
            lower.endsWith(".exe")->"Windows executable: not directly Android compatible."
            lower.endsWith(".zip")->"ZIP: open the full game as HTML5 VN if it contains index.html; otherwise identify its engine."
            else->"Use an Android app supporting this file format."
        }
        AlertDialog.Builder(a).setTitle(name.ifBlank{"Selected file"})
            .setMessage(engine)
            .setPositiveButton("Open with Android app"){_,_->
                runCatching{
                    a.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri,a.contentResolver.getType(uri)?:"application/octet-stream")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }.onFailure{note(a,"No installed app can open this format")}
            }.setNegativeButton("Close",null).show()
    }

    fun route(a:Activity,raw:String,folder:String){
        if(raw.startsWith("magnet:?",true)){openMagnet(a,raw,folder);return}
        if(!SearchRanking.validUrl(raw)){
            note(a,"Enter a valid HTTPS URL or magnet link")
            return
        }
        if(folder=="Comics" && NhentaiChapters.isGallery(raw)){
            ChapterQueue(a).use{it.enqueue(raw,"NHentai gallery "+
                NhentaiChapters.galleryId(raw).orEmpty())}
            ChapterArchives.start(a)
            note(a,"Complete image gallery queued as ZIP; no CBZ/PDF conversion")
            return
        }
        // Recognize entire novel series regardless of which entry point routed
        // the URL. The universal direct-file action must never download HTML
        // as if it were an EPUB, or silently skip the complete-book exporter.
        if(NovelEpubParser.supportedSeries(raw)){
            val title=URI(raw).path.trimEnd('/').substringAfterLast('/')
                .replace('-',' ').ifBlank{"Novel"}
            val fresh=NovelEpubService.enqueue(a,raw,title)
            val archive=if(NovelEpubParser.readableZipSeries(raw))
                "one readable ZIP (HTML chapters and EPUB)" else "one EPUB"
            note(a,if(fresh) "Complete novel queued as $archive"
                else "Novel already queued or saved; existing progress retained")
            return
        }
        val path=runCatching{URI(raw).path.orEmpty().lowercase()}.getOrDefault("")
        when{
            path.endsWith(".torrent")->fetchTorrent(a,raw,folder)
            raw.contains("nyaa.si/view/")||raw.contains("sukebei.nyaa.si/view/")||
                raw.contains("thepiratebay.")||raw.contains("1337x.")||
                (URI(raw).host.orEmpty().removePrefix("www.")=="ggbases.com" &&
                    path=="/view.so")||
                (URI(raw).host.orEmpty().removePrefix("www.")=="onejav.com" &&
                    path.startsWith("/torrent/"))->
                openTorrentSource(a,raw,folder)
            else->http(a,raw,folder)
        }
    }
    fun downloadFile(a:Activity,url:String,folder:String,filename:String,
                     referer:String?=null){
        http(a,url,folder,filename,referer)
    }
    private fun http(a:Activity,url:String,folder:String,filename:String?=null,
                     referer:String?=null){
        if(!url.startsWith("https://")){note(a,"Use an HTTPS download URL");return}
        // VideoFlow owns the mandatory full-runtime gate. Direct DownloadManager
        // requests would bypass the no-teasers/no-shortened-releases rule.
        if(folder=="Videos"){
            val accepted=com.asterion.videoflow.VideoDb(a).use{
                it.addVideo(url,filename?:url)
            }
            if(accepted){
                com.asterion.videoflow.VideoEngine.launch(a,false)
                note(a,"Queued for full-version verification in VideoFlow")
            }else note(a,"Video already queued, held or saved in VideoFlow")
            return
        }
        if(!CrossSourceDedup(a).use{it.reserve(url)}){
            note(a,"This exact original is already queued or recorded · Downloads")
            return
        }
        try{
            val uri=URI(url)
            val file=(filename?:uri.path.orEmpty().substringAfterLast('/').ifBlank{"download"})
                .replace(Regex("[^A-Za-z0-9._-]"),"_").take(100)
            require(folder!="Books" || file.endsWith(".epub",true)){"Books only accepts EPUB files"}
            val d=a.getSystemService(DownloadManager::class.java)
            val request=DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(file)
                setDescription("Asterion $folder")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                    "AsterionDownloader/$folder/$file")
                addRequestHeader("User-Agent",com.azrael.galleryflow.HttpClient.USER_AGENT)
                SiteSession.cookie(url)?.let{addRequestHeader("Cookie",it)}
                if(!referer.isNullOrBlank())
                    privacyReferer(url,referer)?.let{addRequestHeader("Referer",it)}
            }
            val id=d.enqueue(request)
            CrossSourceDedup(a).use{it.attach(url,id)}
            DirectDownloadTracker.record(a,id,file,folder,url)
            DirectDownloadRetry.remember(a,id,url,folder,file,referer)
            note(a,"Download queued: Downloads/AsterionDownloader/$folder")
        }catch(t:Exception){
            runCatching{CrossSourceDedup(a).use{it.release(url)}}
            note(a,"Download rejected: "+t.message)
        }
    }

    fun openTorrentSource(a:Activity,url:String,folder:String="Games"){
        if(!SearchRanking.validUrl(url)){note(a,"Invalid torrent page URL");return}
        thread(name="asterion-torrent-resolver"){
            val failure=runCatching{
                val page=Jsoup.connect(url).userAgent("Mozilla/5.0 (Linux; Android 17) Chrome/131.0")
                    .timeout(25000).maxBodySize(2000000).followRedirects(true).get()
                val magnets=page.select("a[href^=magnet:]").map{it.attr("href")}
                val files=page.select("a[href$=.torrent],a[href*='.torrent?']")
                    .map{it.absUrl("href")}.filter{SearchRanking.validUrl(it)}
                val magnet=magnets.firstOrNull()
                if(magnet!=null)a.runOnUiThread{openMagnet(a,magnet,folder)}
                else if(files.isNotEmpty())a.runOnUiThread{fetchTorrent(a,files.first(),folder)}
                else a.runOnUiThread {
                    note(a,"No magnet or torrent descriptor was found on that page. Opening original listing.")
                    a.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))
                }
            }.exceptionOrNull()
            if(failure!=null)a.runOnUiThread {
                note(a,"Torrent page unavailable: "+failure.message)
                runCatching{a.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}
            }
        }
    }

    private fun openMagnet(a:Activity,url:String,folder:String){
        TorrentTransfers.prepareMagnet(a,url,folder)
    }
    private fun fetchTorrent(a:Activity,url:String,folder:String){
        thread(name="asterion-torrent-metadata") {
            val result=runCatching{
                val u=URL(url)
                require(u.protocol=="https"){"Only HTTPS torrent metadata"}
                val response=u.openConnection() as HttpURLConnection
                response.connectTimeout=25000;response.readTimeout=25000
                response.instanceFollowRedirects=true
                response.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 17)")
                SiteSession.cookie(url)?.let{response.setRequestProperty("Cookie",it)}
                require(response.responseCode in 200..299){"HTTP "+response.responseCode}
                val bytes=response.inputStream.use{input->
                    val buf=ByteArray(8*1024*1024+1)
                    val stream=java.io.ByteArrayOutputStream()
                    while(true){
                        val size=input.read(buf,0,8192)
                        if(size<0)break
                        stream.write(buf,0,size)
                        require(stream.size()<=8*1024*1024){"Torrent descriptor exceeds 8 MB"}
                    }
                    stream.toByteArray()
                }
                require(bytes.size>32 && bytes[0]== 'd'.code.toByte()){
                    "Not a torrent descriptor"
                }
                val hash=MessageDigest.getInstance("SHA-256")
                    .digest(bytes).take(10).joinToString(""){"%02x".format(it)}
                a.runOnUiThread { TorrentTransfers.prepareDescriptor(a,bytes,folder) }
            }.exceptionOrNull()
            if(result!=null)a.runOnUiThread{note(a,"Torrent metadata error: "+result.message)}
        }
    }
}
