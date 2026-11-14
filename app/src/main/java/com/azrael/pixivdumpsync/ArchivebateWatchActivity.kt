package com.azrael.pixivdumpsync

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast
import com.asterion.videoflow.ArchivebateSelectedMedia
import com.asterion.videoflow.VideoDb
import com.asterion.videoflow.VideoEngine
import com.asterion.videoflow.WebDiscovery
import org.json.JSONTokener
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Exact selected Archivebate player, not a generic web browser or catalogue.
 *
 * Public browser tests showed that the /watch/<id> page's surrounding MP4s
 * are recommendation previews. Only the first-party published Mixdrop iframe
 * is shown here, so a user's manual Play/download action can never silently
 * save an unrelated teaser. No download starts without an explicit tap.
 */
class ArchivebateWatchActivity:Activity() {
    companion object {
        const val WATCH_URL="archivebate_selected_watch"
        const val VIDEO_TITLE="archivebate_selected_title"
    }
    private var player:WebView?=null
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        val watch=intent.getStringExtra(WATCH_URL).orEmpty()
        val recordingId=runCatching{java.net.URI(watch).path
            .orEmpty().trimEnd('/').substringAfterLast('/')}
            .getOrDefault("").takeIf{it.matches(Regex("[0-9]{1,16}"))}
            .orEmpty()
        ArchivebateDiagnostics.record(this,"watch_open","recording="+recordingId)
        val title=intent.getStringExtra(VIDEO_TITLE)
            ?.take(180)?.ifBlank{"Archivebate recording"}?:"Archivebate recording"
        if(!WebDiscovery.isVideoPage(watch) ||
            !watch.startsWith("https://archivebate.com/watch/")){
            Toast.makeText(this,"Invalid selected Archivebate recording",
                Toast.LENGTH_LONG).show()
            finish();return
        }

        val ui=NocturneDesign
        val root=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(ui.background)
        }
        root.addView(ui.text(this,"Selected Archivebate recording",
            18f,ui.white,true).apply{
            setPadding(ui.px(this@ArchivebateWatchActivity,14),
                ui.px(this@ArchivebateWatchActivity,12),
                ui.px(this@ArchivebateWatchActivity,14),
                ui.px(this@ArchivebateWatchActivity,5))
        })
        val info=ui.text(this,
            "Play the chosen recording on its actual publisher player. "+
            "Only an accessible original MP4 can be downloaded; recommendation "+
            "previews and advertisements are excluded.",12f,ui.secondary)
        root.addView(info)
        val video=WebView(this)
        player=video
        video.settings.javaScriptEnabled=true
        video.settings.domStorageEnabled=true
        video.settings.allowFileAccess=false
        video.settings.allowContentAccess=false
        video.settings.mediaPlaybackRequiresUserGesture=true
        video.settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(video,false)
        video.webChromeClient=WebChromeClient()
        video.webViewClient=object:WebViewClient(){
            override fun shouldOverrideUrlLoading(
                view:WebView,request:WebResourceRequest):Boolean{
                val host=request.url.host.orEmpty().lowercase(Locale.ROOT)
                    .removePrefix("www.")
                // Player resources are not navigations. Deny main-frame ads.
                return request.isForMainFrame &&
                    (request.url.scheme!="https" || host!="mixdrop.ag")
            }
        }
        root.addView(video,LinearLayout.LayoutParams(-1,0,1f))
        val actions=ui.row(this)
        actions.addView(ui.action(this,"BACK"){
            finish()
        },LinearLayout.LayoutParams(0,-2,1f))
        actions.addView(ui.action(this,"DOWNLOAD PLAYING ORIGINAL",true){
            val web=player
            if(web==null)return@action
            web.evaluateJavascript("""(function(){
                const v=document.querySelector('video');
                return v?.currentSrc||v?.src||
                    v?.querySelector('source[src]')?.src||'';
            })()"""){response->
                val observed=runCatching{
                    JSONTokener(response).nextValue() as? String
                }.getOrNull().orEmpty()
                if(!ArchivebateSelectedMedia.isSelectedMedia(observed)){
                    ArchivebateDiagnostics.record(this,"no_selected_media",
                        "Player has no verified original MP4 after user playback")
                    Toast.makeText(this,
                        "The selected player has not exposed an original MP4. "+
                        "Play the recording first; protected or unavailable media "+
                        "cannot be downloaded.",Toast.LENGTH_LONG).show()
                    return@evaluateJavascript
                }
                val queued=VideoDb(this).use{db->
                    db.addVideo(observed,title,0L,origin="user")
                }
                if(queued){
                    ArchivebateDiagnostics.record(this,"queued_original")
                    VideoEngine.launch(applicationContext,false)
                    Toast.makeText(this,
                        "Selected original queued. Verify its transfer in Downloads.",
                        Toast.LENGTH_LONG).show()
                    finish()
                }else{
                    ArchivebateDiagnostics.record(this,"already_present")
                    Toast.makeText(this,"This original is already queued or saved",
                        Toast.LENGTH_LONG).show()
                }
            }
        },LinearLayout.LayoutParams(0,-2,2f))
        root.addView(actions)
        root.addView(ui.action(this,"COPY PLAYER DIAGNOSTIC"){
            getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText(
                    "Archivebate player diagnostics",ArchivebateDiagnostics.report(this)))
            Toast.makeText(this,"Archivebate diagnostic copied",Toast.LENGTH_SHORT).show()
        })
        setContentView(root)

        info.text="Locating this exact recording's published player…"
        thread(name="asterion-selected-archivebate-player"){
            ArchivebateDiagnostics.record(applicationContext,"embed_lookup_started")
            val response=runCatching{
                WebDiscovery.embeddedCandidates(watch,applicationContext)
                    .firstOrNull{ArchivebateSelectedMedia.isSelectedEmbed(watch,it)}
                    ?:error("No original player was published on the selected recording")
            }
            runOnUiThread{
                if(isFinishing || isDestroyed)return@runOnUiThread
                response.onSuccess{embed->
                    ArchivebateDiagnostics.record(this,"selected_embed_found")
                    info.text="Play the selected recording, then download "+
                        "only its actual original."
                    video.loadUrl(embed,mapOf("Referer" to watch))
                }.onFailure{error->
                    ArchivebateDiagnostics.record(this,"embed_lookup_failed",
                        error.javaClass.simpleName+": "+error.message.orEmpty())
                    info.text="Selected original unavailable: "+
                        error.message.orEmpty().take(170)+
                        " · reconnect the site or retry this recording."
                }
            }
        }
    }
    override fun onDestroy(){
        player?.apply{stopLoading();destroy()}
        player=null
        super.onDestroy()
    }
}
