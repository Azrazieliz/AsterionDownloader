package com.asterion.videoflow

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URI
import java.net.URLDecoder
import org.jsoup.Jsoup
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONTokener

/**
 * Resolve only the original selected Archivebate recording's published player.
 * The site's /watch/<id> page contains UNRELATED recommendation MP4s, which
 * are never suitable original media for that selected recording.
 *
 * Real Chrome verification (2026-11-13) observed Archivebate /watch/<id> ->
 * its exact Mixdrop /e/<id> player -> CDN video/mp4. yt-dlp cannot extract
 * the Mixdrop embed URL. A user-selected video can instead be resolved from
 * the visible HTML5 player, without altering access controls or DRM.
 */
internal object ArchivebateSelectedMedia {
    /** Listing pages, recommendations and thumbnails must never enter the
     * selected recording's player-transfer lane. */
    fun isSelectedArticle(watch:String):Boolean {
        val u=runCatching{URI(watch)}.getOrNull()?:return false
        return u.scheme=="https" && u.userInfo==null && u.port==-1 &&
            u.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")==
                "archivebate.com" &&
            Regex("""^/(?:u/)?watch/[0-9]{1,16}/?$""").matches(u.path.orEmpty()) &&
            u.rawQuery.isNullOrBlank()
    }
    fun isSelectedEmbed(watch:String,embed:String):Boolean {
        val source=runCatching{URI(watch)}.getOrNull()?:return false
        val player=runCatching{URI(embed)}.getOrNull()?:return false
        return source.scheme=="https" && source.userInfo==null &&
            source.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")=="archivebate.com" &&
            Regex("""^/(?:u/)?watch/[0-9]{1,16}/?$""").matches(source.path.orEmpty()) &&
            player.scheme=="https" && player.userInfo==null &&
            player.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")=="mixdrop.ag" &&
            Regex("""^/e/[A-Za-z0-9_-]{5,64}/?$""").matches(player.path.orEmpty()) &&
            player.rawQuery.isNullOrBlank()
    }

    /** Mixdrop currently redirects its published /e/<id> to mxdrop.click.
     * Allow only the identical selected player path on its observed canonical
     * domain, not popunders, other video IDs or arbitrary third parties.
     * The source /watch/ must still attest the original mixdrop.ag embed.
     */
    internal fun selectedRedirect(original:String,destination:String):Boolean {
        val start=runCatching{URI(original)}.getOrNull()?:return false
        val target=runCatching{URI(destination)}.getOrNull()?:return false
        val initialHost=start.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        val finalHost=target.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        return start.scheme=="https" && target.scheme=="https" &&
            start.userInfo==null && target.userInfo==null &&
            initialHost=="mixdrop.ag" &&
            finalHost in setOf("mixdrop.ag","mxdrop.click") &&
            start.path==target.path &&
            Regex("""^/e/[A-Za-z0-9_-]{5,64}/?$""").matches(target.path.orEmpty()) &&
            start.rawQuery.isNullOrBlank() && target.rawQuery.isNullOrBlank() &&
            start.port==-1 && target.port==-1
    }

    /** Accept the precise video CDN host observed inside the selected player,
     * not an ad domain, parent-page preview, playlist or guessed media URL.
     */
    fun isSelectedMedia(raw:String):Boolean {
        val u=runCatching{URI(raw)}.getOrNull()?:return false
        val hostname=u.host.orEmpty().lowercase(Locale.ROOT)
        // Real selected Mixdrop source (HTTP 206, MP4 ftyp) is on
        // gfve4dog1.mxcontent.net, not the older cdn.freefile.io host.
        // Require a proper mxcontent subdomain and exact MP4 path; an
        // unrelated advertising host or parent-page preview is not media.
        val publisherCdn=hostname=="cdn.freefile.io" ||
            Regex("""^[a-z0-9]{6,32}\.mxcontent\.net$""").matches(hostname)
        return u.scheme=="https" && u.userInfo==null && u.port==-1 &&
            u.fragment==null && publisherCdn &&
            Regex("""(?i)\.(mp4|m4v)$""").containsMatchIn(u.path.orEmpty()) &&
            NativeMediaDownloader.isDirectMediaUrl(raw)
    }

    private fun assigned(source:String,key:String):String? {
        val anchors=Regex("""\bMDCore\."""+Regex.escape(key)+"""\s*=\s*""")
            .findAll(source).take(2).toList()
        if(anchors.size!=1)return null
        return runCatching {
            JSONTokener(source.substring(anchors.single().range.last+1))
                .nextValue() as? String
        }.getOrNull()
    }

    /**
     * Public selected Mixdrop /e/<id> contains one P.A.C.K.E.R script:
     * MDCore.wurl="//gfve4dog1.mxcontent.net/...mp4?...";
     * MDCore.referrer="https%3A%2F%2Farchivebate.com%2Fwatch%2F16458050"
     * Both values come from the same exact selected player. Never accept
     * unrelated scripts, previews, other player IDs or arbitrary hosters.
     */
    internal fun fromDecoded(watch:String,embed:String,canonical:String,
                             decoded:String):String? {
        if(!isSelectedEmbed(watch,embed) ||
            !selectedRedirect(embed,canonical) ||
            decoded.length !in 100..50_000 ||
            !decoded.contains("MDCore.wurl") ||
            !decoded.contains("MDCore.referrer"))
            return null
        val rawRef=assigned(decoded,"referrer")?:return null
        val ref=runCatching{URLDecoder.decode(rawRef,"UTF-8")}
            .getOrNull()?:return null
        if(ref!=watch)return null
        val name=assigned(decoded,"vfile")?:return null
        if(!name.matches(Regex("""[A-Za-z0-9._\-]{4,180}\.mp4""")))
            return null
        val raw=assigned(decoded,"wurl")?:return null
        val url=runCatching{URI(canonical).resolve(raw).normalize()}
            .getOrNull()?:return null
        if(!isSelectedMedia(url.toString()))return null
        // Older public selected embeds use cdn.freefile.io; the verified
        // current Mixdrop player has mxcontent.net and an opaque signed URL.
        return url.toASCIIString()
    }

    internal fun publishedPlayerHtml(watch:String,embed:String,
                                     canonical:String,html:String):String? {
        if(!isSelectedEmbed(watch,embed) ||
            !selectedRedirect(embed,canonical) ||
            html.length !in 150..1_500_000)return null
        val page=Jsoup.parse(html,canonical)
        val media=LinkedHashSet<String>()
        for(script in page.select("script:not([src])").take(70)){
            val body=script.data().ifBlank{script.html()}
            if(!body.contains("eval(function(p,a,c,k,e,d)"))continue
            val decoded=RoshyPackedOriginalSource.unpack(body)?:continue
            val link=fromDecoded(watch,embed,canonical,decoded)?:continue
            media.add(link)
        }
        // Two original players would not prove the user's selected scene.
        return media.singleOrNull()
    }

    /** Obtain only the selected canonical /e/<id> HTML through a bounded
     * publisher-authorized HTTPS GET. No script execution or ad navigation.
     * If a site requires interactive playback the existing WebView remains
     * the fallback; do not weaken its session or access checks.
     */
    private fun publicPackedPlayer(context:Context,watch:String,
                                   embed:String):String? {
        if(!isSelectedEmbed(watch,embed))return null
        fun load(url:String):org.jsoup.Connection.Response =
            Jsoup.connect(url)
                .userAgent(NativeMediaDownloader.USER_AGENT)
                .referrer(watch)
                .header("Accept-Encoding","identity")
                .ignoreHttpErrors(true).followRedirects(false)
                .timeout(17_000).maxBodySize(1_500_000)
                .apply { NetworkRoute.javaProxy(context,watch)
                    ?.let{proxy(it)} }.execute()
        val first=load(embed)
        val source=if(first.statusCode() in 300..399) {
            val location=first.header("Location")
                ?.takeIf{it.isNotBlank()}?:return null
            val resolved=runCatching{
                URI(embed).resolve(location).normalize().toString()
            }.getOrNull()?:return null
            if(!selectedRedirect(embed,resolved))return null
            resolved to load(resolved)
        }else embed to first
        if(source.second.statusCode()!=200)return null
        return publishedPlayerHtml(watch,embed,source.first,
            source.second.body())
    }

    /** VideoFlow workers use isolated processes. Browser inspection must run
     * through the non-exported main-process owner of the connected session.
     */
    fun resolve(context:Context,watch:String,embed:String):String {
        require(isSelectedEmbed(watch,embed)){
            "Archivebate selected watch page did not publish a valid player"
        }
        // A first-party /watch/ page can publish this exact Mixdrop player
        // publicly after the site's ordinary Continue action. The previous
        // unconditional SiteSession.bound gate prevented every public
        // selected recording from reaching its real published player.
        // WebView will still enforce actual publisher/network restrictions.
        val main=if(Build.VERSION.SDK_INT>=28)
            android.app.Application.getProcessName()==context.packageName else true
        if(!main){
            val provider=Uri.parse(
                "content://"+context.packageName+".session-cookies")
            val result=context.contentResolver.call(provider,
                "resolve_archivebate_selected",watch,
                android.os.Bundle().apply{putString("embed",embed)})
            val raw=result?.getString("media").orEmpty()
            require(isSelectedMedia(raw)){
                result?.getString("error")?:
                    "Selected recording has no accessible player video source"
            }
            return raw
        }
        return fromConnectedPlayer(context,watch,embed)
    }

    /** Called by the main app ONLY after the exact selected iframe was checked.
     * No browsing/search, automatic site traversal, previews or account bypass.
     */
    fun fromConnectedPlayer(context:Context,watch:String,embed:String):String {
        require(isSelectedEmbed(watch,embed)){"Invalid selected publisher iframe"}
        check(Looper.myLooper()!=Looper.getMainLooper()){
            "Player inspection must start off Android's UI thread"
        }
        // Prefer publisher-attested public MDCore.wurl directly over an
        // unpredictable WebView video.currentSrc probe. Live selected MXCDN
        // responded HTTP 206 / video/mp4 with MP4 ftyp. The WebView remains
        // a fallback when the public player is genuinely unavailable.
        runCatching{publicPackedPlayer(context,watch,embed)}
            .getOrNull()?.let{return it}
        // No artificial account requirement for genuinely public players.
        // Authenticated pages still require their valid browser session
        // upstream before the exact selected embed can be discovered.
        val main=Handler(Looper.getMainLooper())
        val done=CountDownLatch(1)
        val result=AtomicReference<String?>()
        val error=AtomicReference<String?>()
        val browser=AtomicReference<WebView?>()
        main.post {
            try {
                val view=WebView(context)
                browser.set(view)
                view.settings.javaScriptEnabled=true
                view.settings.domStorageEnabled=true
                view.settings.mediaPlaybackRequiresUserGesture=false
                view.settings.allowFileAccess=false
                view.settings.allowContentAccess=false
                view.settings.setSupportMultipleWindows(false)
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(view,false)
                var inspected=0
                fun inspect(){
                    if(done.count==0L)return
                    view.evaluateJavascript("""(function(){
                        const v=document.querySelector('video');
                        if(!v)return '';
                        v.muted=true;
                        if(v.paused){
                            try{const p=v.play();if(p&&p.catch)p.catch(()=>{});}catch(e){}
                        }
                        return v.currentSrc||v.src||
                            v.querySelector('source[src]')?.src||'';
                    })()"""){json->
                        if(done.count==0L)return@evaluateJavascript
                        val observed=runCatching{
                            JSONTokener(json).nextValue() as? String
                        }.getOrNull().orEmpty()
                        if(isSelectedMedia(observed)){
                            result.set(observed)
                            done.countDown()
                        }else if(inspected++<18){
                            main.postDelayed({inspect()},1_100L)
                        }else{
                            error.set("Selected Archivebate player exposed no accessible original MP4")
                            done.countDown()
                        }
                    }
                }
                view.webViewClient=object:WebViewClient(){
                    override fun shouldOverrideUrlLoading(
                        v:WebView,request:WebResourceRequest):Boolean{
                        // Do not launch popup ads or navigate away from the
                        // actual user-selected published Mixdrop player.
                        val dest=request.url
                        return request.isForMainFrame &&
                            !selectedRedirect(embed,dest.toString())
                    }
                    override fun onPageFinished(v:WebView,url:String){
                        if(done.count>0L)main.postDelayed({inspect()},1_400L)
                    }
                    override fun onReceivedError(v:WebView,request:WebResourceRequest,
                                                 problem:android.webkit.WebResourceError){
                        if(request.isForMainFrame && done.count>0L){
                            error.set("Selected video player failed to load")
                            done.countDown()
                        }
                    }
                }
                view.loadUrl(embed, mapOf("Referer" to watch))
            }catch(t:Throwable){
                error.set("Selected player initialization failed: "+
                    t.javaClass.simpleName)
                done.countDown()
            }
        }
        val ok=done.await(38L,TimeUnit.SECONDS)
        main.post{runCatching{
            browser.getAndSet(null)?.apply{stopLoading();destroy()}
        }}
        if(!ok)throw IllegalStateException(
            "Selected Archivebate player took too long to expose accessible video")
        val media=result.get()
        require(isSelectedMedia(media.orEmpty())){
            error.get()?:"Selected player did not expose original media"
        }
        return media!!
    }
}
