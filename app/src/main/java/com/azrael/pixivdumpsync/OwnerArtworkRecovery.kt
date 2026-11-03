package com.azrael.pixivdumpsync

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import com.azrael.galleryflow.SiteLoginActivity
import com.azrael.galleryflow.SiteSession
import org.jsoup.Jsoup
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Creator-owned recovery intake for website pages or owner-exported original URLs.
 *
 * This is intentionally separate from subscriber entitlements: it consumes only
 * files the web server actually serves, current site sessions, and owner-issued
 * signed links. Site-specific storage exports can be added as adapters here.
 *
 * Transfers use Android DownloadManager and Asterion's existing queue tracker.
 * A queued transfer is not represented as successfully recovered until DownloadManager
 * reports STATUS_SUCCESSFUL.
 */
object OwnerArtworkRecovery {
    private const val MAX_SEEDS = 80
    private const val MAX_FILES = 300
    private const val FOLDER = "Images/Recovered"
    private val rejectedPath = Regex(
        "(?i)(?:thumb|thumbnail|preview|sample|lowres|blurred|avatar|sprite|poster|watermark|icon)(?:[/_.-]|$)"
    )
    private val safeName = Regex("[^\\p{L}\\p{N}._-]")

    internal fun seeds(text:String):List<String> =
        text.lineSequence().map{it.trim()}.filter{it.isNotEmpty()}
            .distinct().take(MAX_SEEDS).toList()

    private fun valid(url:String):Boolean {
        if(!SourceIndexParser.safe(url))return false
        val host=runCatching{URI(url).host.orEmpty().lowercase(Locale.ROOT)}
            .getOrDefault("")
        return host!="localhost" && host!="127.0.0.1" &&
            !host.startsWith("127.") && !host.startsWith("10.") &&
            !host.startsWith("192.168.") && !host.endsWith(".local") &&
            !host.matches(Regex("172\\.(1[6-9]|2[0-9]|3[01])\\..*"))
    }

    private fun fileName(url:String, hint:String=""):String {
        val provided=hint.substringAfterLast('/').substringAfterLast('\\')
        val fallback=runCatching{URI(url).path.orEmpty().substringAfterLast('/')}
            .getOrDefault("")
        val value=provided.ifBlank{fallback}
            .replace(safeName,"_").take(110)
        return value.ifBlank{"original.jpg"}
    }

    /** No page-wide crawling: only explicitly linked originals in supplied pages. */
    internal fun extract(html:String,page:String):List<AccessibleOriginal> {
        val doc=Jsoup.parse(html,page)
        val result=LinkedHashMap<String,AccessibleOriginal>()
        fun add(raw:String,hint:String=""){
            if(raw.isBlank())return
            val resolved=runCatching{URI(page).resolve(raw.trim()).toString()}
                .getOrNull()?:return
            if(!valid(resolved))return
            val uri=URI(resolved)
            if(rejectedPath.containsMatchIn(uri.path.orEmpty()))return
            val name=fileName(resolved,hint)
            val ext=AuthorizedOriginalResolver.mediaType(resolved)
                .ifBlank{AuthorizedOriginalResolver.mediaType(name)}
            if(ext!="image")return
            result.putIfAbsent(resolved,AccessibleOriginal(resolved,name,"image",page))
        }
        // Original attachments and direct image links, not arbitrary page thumbnails.
        for(element in doc.select("a[href],a[data-download-url],button[data-download-url],"+
            "a[data-file-url],button[data-file-url]").take(1200)){
            val href=element.attr("data-download-url")
                .ifBlank{element.attr("data-file-url")}
                .ifBlank{element.attr("href")}
            val filename=element.attr("download")
                .ifBlank{element.attr("data-filename")}
            add(href,filename)
        }
        for(image in doc.select("img[data-original],img[data-full],"+
            "img[data-download-url],img[data-file-url]").take(500)){
            val direct=image.attr("data-original").ifBlank{image.attr("data-full")}
                .ifBlank{image.attr("data-download-url")}
                .ifBlank{image.attr("data-file-url")}
            add(direct,image.attr("data-filename"))
        }
        return result.values.take(MAX_FILES).toList()
    }

    /** Resolve a source, reporting access denial instead of pretending it is recoverable. */
    fun resolve(url:String):List<AccessibleOriginal> {
        require(valid(url)){"A public HTTPS page or signed original URL is required"}
        if(AuthorizedOriginalResolver.mediaType(url)=="image"){
            return listOf(AccessibleOriginal(url,fileName(url),"image",url))
        }
        AuthorizedEndpointInspector.inspect(url,"ARTWORK")?.let{original->
            if(original.kind=="image")return listOf(original)
        }
        val request=Jsoup.connect(url).timeout(16000).maxBodySize(4_000_000)
            .userAgent("Mozilla/5.0 (Android) AsterionDownloader/1.3")
            .header("Accept","text/html,application/xhtml+xml")
            .ignoreHttpErrors(true).followRedirects(true)
        SiteSession.cookie(url)?.let{request.header("Cookie",it)}
        val response=request.execute()
        val destination=response.url().toString()
        require(valid(destination)){"Download page redirected to an untrusted URL"}
        when(response.statusCode()){
            401,403->throw OriginalAccessException(OriginalAccessCause.SIGN_IN,
                "Website login or creator authorization is required")
            402->throw OriginalAccessException(OriginalAccessCause.ENTITLEMENT,
                "Customer paywall is active; use the owner's storage export instead")
            404,410->throw OriginalAccessException(OriginalAccessCause.NOT_FOUND,
                "The requested page no longer exists")
            429->throw OriginalAccessException(OriginalAccessCause.RATE_LIMIT,
                "The site is rate-limiting requests")
        }
        check(response.statusCode() in 200..299){
            "Website returned HTTP "+response.statusCode()
        }
        val path=URI(destination).path.orEmpty().lowercase(Locale.ROOT)
        if(path.matches(Regex(".*/(?:login|signin|sign-in|auth)/?"))){
            throw OriginalAccessException(OriginalAccessCause.SIGN_IN,
                "Page redirected to website login")
        }
        val links=extract(response.parse().outerHtml(),destination)
        if(links.isEmpty())throw OriginalAccessException(OriginalAccessCause.NO_ORIGINAL,
            "No original artwork links exposed on this page; owner-side export may be needed")
        return links
    }

    internal fun uniqueFileName(original:AccessibleOriginal):String {
        val hash=MessageDigest.getInstance("SHA-256")
            .digest(original.url.toByteArray(Charsets.UTF_8))
            .take(6).joinToString(""){"%02x".format(it)}
        return "recovered_"+hash+"_"+fileName(original.url,original.filename).take(95)
    }

    data class Summary(val pages:Int,val originals:Int,val queued:Int,
        val blocked:Int,val missing:Int,val unresolved:Int,val invalid:Int)

    private fun queue(activity:Activity,original:AccessibleOriginal):Boolean {
        if(!valid(original.url) || original.kind!="image")return false
        val filename=uniqueFileName(original)
        val manager=activity.getSystemService(DownloadManager::class.java)
        val request=DownloadManager.Request(Uri.parse(original.url)).apply{
            setTitle(filename)
            setDescription("Asterion owner artwork recovery")
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                "AsterionDownloader/"+FOLDER+"/"+filename)
            SiteSession.cookie(original.url)?.let{addRequestHeader("Cookie",it)}
            ResourceDownloads.privacyReferer(original.url,original.referer)?.let{
                addRequestHeader("Referer",it)
            }
        }
        val id=manager.enqueue(request)
        DirectDownloadTracker.record(activity,id,filename,"Images",original.url)
        return id>0
    }

    fun recover(activity:Activity,raw:String,progress:(String)->Unit,
        finished:(Summary)->Unit){
        val urls=seeds(raw)
        require(urls.isNotEmpty()){"Paste at least one HTTPS artwork page or file URL"}
        thread(name="asterion-owner-artwork-recovery"){
            val seen=HashSet<String>()
            var queued=0
            var blocked=0
            var missing=0
            var unresolved=0
            var invalid=0
            var pages=0
            for((position,url) in urls.withIndex()){
                if(!valid(url)){invalid++;continue}
                pages++
                val result=runCatching{resolve(url)}
                result.onSuccess{links->
                    for(link in links){
                        if(seen.size>=MAX_FILES)break
                        if(!seen.add(link.url))continue
                        if(runCatching{queue(activity,link)}.getOrDefault(false))queued++
                        else unresolved++
                    }
                }.onFailure{error->
                    when((error as? OriginalAccessException)?.reason){
                        OriginalAccessCause.SIGN_IN,OriginalAccessCause.ENTITLEMENT->blocked++
                        OriginalAccessCause.NOT_FOUND->missing++
                        else->unresolved++
                    }
                }
                if(position%4==0 || position==urls.lastIndex){
                    val message="Checked "+(position+1)+"/"+urls.size+" URLs · "+
                        queued+" transfers queued · "+blocked+" access-blocked"
                    activity.runOnUiThread{
                        if(!activity.isDestroyed)progress(message)
                    }
                }
                if(seen.size>=MAX_FILES)break
            }
            val result=Summary(pages,seen.size,queued,blocked,missing,unresolved,invalid)
            activity.runOnUiThread{
                if(!activity.isDestroyed)finished(result)
            }
        }
    }

    fun render(activity:Activity,parent:LinearLayout){
        val ui=NocturneDesign
        val card=ui.card(activity)
        card.addView(ui.text(activity,"RECOVER MY UPLOADED ARTWORKS",18f,ui.white,true),
            ui.lp(activity,5))
        card.addView(ui.text(activity,
            "For your own website: paste original file URLs, owner-exported signed URLs, "+
            "or pages exposing downloadable originals (one URL per line). Existing "+
            "website sessions are reused. A customer subscription is not needed "+
            "when your hosting or storage provider supplies an owner export.",
            12f,ui.secondary),ui.lp(activity,8))
        val urls=EditText(activity).apply{
            hint="https://your-site.example/my-artwork-post\nhttps://storage.example/original.jpg?signature=..."
            minLines=3
            maxLines=9
            setSingleLine(false)
            imeOptions=EditorInfo.IME_ACTION_DONE
            textSize=13f
            setTextColor(ui.white)
            setHintTextColor(ui.subdued)
            background=ui.shape(activity,ui.raised,11)
            setPadding(ui.px(activity,12),ui.px(activity,10),
                ui.px(activity,12),ui.px(activity,10))
        }
        card.addView(urls,ui.lp(activity,8))
        val status=ui.text(activity,"Recovered files appear in Downloads > "+
            "AsterionDownloader > Images > Recovered. Queue status appears under Queue.",
            12f,ui.secondary)
        card.addView(ui.action(activity,"CONNECT WEBSITE SESSION",false){
            val first=seeds(urls.text.toString()).firstOrNull()
            if(first==null || !SourceIndexParser.safe(first)){
                status.text="Paste the website HTTPS address before connecting."
            }else{
                activity.startActivity(Intent(activity,SiteLoginActivity::class.java)
                    .putExtra(SiteLoginActivity.EXTRA_URL,first))
            }
        },ui.lp(activity,8))
        card.addView(ui.action(activity,"RECOVER ACCESSIBLE ORIGINALS",true){
            if(!AppPower.enabled(activity)){
                status.text="Downloads are paused. Enable app downloads first."
            }else{
                val raw=urls.text.toString()
                if(seeds(raw).isEmpty()){
                    status.text="Paste original URLs or artwork-page URLs first."
                }else{
                    status.text="Examining original links; no customer purchase will be attempted."
                    recover(activity,raw,{status.text=it},{summary->
                        status.text=summary.queued.toString()+" originals queued from "+
                            summary.pages+" pages ("+summary.originals+" distinct links). "+
                            summary.blocked+" require site/owner authorization; "+
                            summary.missing+" unavailable; "+summary.unresolved+
                            " unresolved; "+summary.invalid+" invalid. "+
                            "Check Queue for completed or failed downloads."
                    })
                }
            }
        },ui.lp(activity,8))
        card.addView(status,ui.lp(activity,10))
        parent.addView(card,ui.lp(activity,12))
    }
}
