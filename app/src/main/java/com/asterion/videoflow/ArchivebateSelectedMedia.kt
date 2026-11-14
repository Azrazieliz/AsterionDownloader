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
    fun isSelectedEmbed(watch:String,embed:String):Boolean {
        val source=runCatching{URI(watch)}.getOrNull()?:return false
        val player=runCatching{URI(embed)}.getOrNull()?:return false
        return source.scheme=="https" && source.userInfo==null &&
            source.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")=="archivebate.com" &&
            Regex("""^/watch/[0-9]{1,16}/?$""").matches(source.path.orEmpty()) &&
            player.scheme=="https" && player.userInfo==null &&
            player.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")=="mixdrop.ag" &&
            Regex("""^/e/[A-Za-z0-9_-]{5,64}/?$""").matches(player.path.orEmpty()) &&
            player.rawQuery.isNullOrBlank()
    }

    /** Accept the precise video CDN host observed inside the selected player,
     * not an ad domain, parent-page preview, playlist or guessed media URL.
     */
    fun isSelectedMedia(raw:String):Boolean {
        val u=runCatching{URI(raw)}.getOrNull()?:return false
        return u.scheme=="https" && u.userInfo==null &&
            u.host.orEmpty().lowercase(Locale.ROOT)=="cdn.freefile.io" &&
            Regex("""(?i)\.(mp4|m4v)(?:$|/)""").containsMatchIn(u.path.orEmpty()) &&
            NativeMediaDownloader.isDirectMediaUrl(raw)
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
                            (dest.scheme!="https" ||
                                dest.host.orEmpty().lowercase(Locale.ROOT)
                                    .removePrefix("www.")!="mixdrop.ag")
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
