package com.azrael.galleryflow

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import com.azrael.pixivdumpsync.ResourceDownloads
import android.widget.LinearLayout
import android.widget.TextView
import android.view.Gravity
import android.widget.Toast
import java.net.URI
import java.util.Locale

class ProviderWebActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL)?.takeIf { it.startsWith("http") }
            ?: run {
                finish()
                return
            }

        if (shouldUseExternalBrowser(url)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("External browser required")
                .setMessage("This account provider requires a separate browser. "+
                    "Open it explicitly, or remain in Asterion.")
                .setPositiveButton("OPEN EXTERNAL BROWSER"){_,_->
                    runCatching{
                        startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))
                    }.onFailure{
                        Toast.makeText(this,it.message?:"Browser unavailable",
                            Toast.LENGTH_LONG).show()
                    }
                    finish()
                }
                .setNegativeButton("CANCEL"){_,_->finish()}
                .setOnCancelListener{finish()}
                .show()
            return
        }

        window.statusBarColor = Color.rgb(17, 18, 22)
        window.navigationBarColor = Color.rgb(17, 18, 22)

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            text = "Provider page"
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.userAgentString = HttpClient.USER_AGENT
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val link=request.url.toString()
                    val folder=intent.getStringExtra(EXTRA_FOLDER).orEmpty()
                    // Universal site browsing: magnet and torrent links work
                    // regardless of the source module from which browsing began.
                    val torrent=link.startsWith("magnet:?xt=urn:btih:",true) ||
                        (link.startsWith("https://",true) && runCatching{
                            URI(link).path.orEmpty().endsWith(".torrent",true)
                        }.getOrDefault(false))
                    if(torrent){
                        val target=folder.takeIf{it in setOf(
                            "Games","Adult","Books","Videos","Files")} ?: "Files"
                        ResourceDownloads.route(this@ProviderWebActivity,link,target)
                        return true
                    }
                    return false
                }
                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    CookieManager.getInstance().flush()
                    status.text = pageUrl ?: "Provider page"
                }
            }
            webChromeClient = WebChromeClient()
            setDownloadListener(providerDownloadListener())
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(17, 18, 22))
            addView(
                status,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                webView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
        }
        val collect = TextView(this).apply {
            text = "SAVE MEDIA LOADED ON THIS PAGE"
            textSize=12f
            setTextColor(Color.WHITE)
            gravity=Gravity.CENTER
            setPadding(dp(12),dp(12),dp(12),dp(12))
            setBackgroundColor(Color.rgb(45,48,57))
            isClickable=true
            isFocusable=true
            contentDescription="Queue accessible media links loaded in this website"
            setOnClickListener{collectVisibleMedia()}
        }
        root.addView(collect,1,LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        webView.loadUrl(url)
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            CookieManager.getInstance().flush()
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun shouldUseExternalBrowser(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
        // Ordinary storage and creator sites remain inside Asterion. Google
        // OAuth forbids embedded WebViews and must explicitly use a browser.
        return host == "accounts.google.com"
    }

    /** Browser DOM scan only. Media absent from the signed-in page is not
     * fabricated or obtained from undocumented account APIs.
     */
    private fun collectVisibleMedia() {
        val page=webView.url.orEmpty()
        status.text="Finding media already loaded in the browser…"
        webView.evaluateJavascript("""(function(){
            const found=[];const seen=new Set();
            const add=(raw,kind,name)=>{
                if(!raw || found.length>=150)return;
                try {
                    const u=new URL(raw,location.href);
                    if(u.protocol!=='https:'||seen.has(u.href))return;
                    seen.add(u.href);
                    found.push({url:u.href,kind:kind,filename:name||''});
                }catch(e){}
            };
            const container=document.querySelector('main')||document.body;
            if(!container)return JSON.stringify([]);
            const fileExt=/\\.(?:jpg|jpeg|png|gif|webp|avif|mp4|m4v|mov|webm|mp3|flac|m4a|wav|ogg|opus|zip|7z|rar|epub|apk|blend|fbx|glb|gltf|stl|pmx|pmd)(?:$|[?#])/i;
            for(const a of container.querySelectorAll('a[href]')){
                if(a.hasAttribute('download')||fileExt.test(a.href))
                    add(a.href,'file',a.getAttribute('download')||'');
            }
            for(const v of container.querySelectorAll('video')){
                add(v.currentSrc||v.src,'video','');
                for(const x of v.querySelectorAll('source[src]'))
                    add(x.src,'video','');
            }
            for(const img of container.querySelectorAll('img')){
                if(img.naturalWidth<850||img.naturalHeight<500)continue;
                const original=img.getAttribute('data-original')||
                    img.getAttribute('data-full')||img.currentSrc||img.src;
                add(original,'image','');
            }
            return JSON.stringify(found);
        })()"""){raw->
            val parsed=runCatching{
                val wrapped=org.json.JSONTokener(raw).nextValue() as String
                val list=org.json.JSONArray(wrapped)
                (0 until minOf(list.length(),150)).mapNotNull{n->
                    val item=list.optJSONObject(n)?:return@mapNotNull null
                    com.azrael.pixivdumpsync.VisibleMedia(
                        item.optString("url"),item.optString("kind"),
                        item.optString("filename"))
                }.filter{com.azrael.pixivdumpsync.VisibleMediaSupport.accepted(page,it)}
            }
            parsed.onFailure{status.text="Browser media scan failed: "+it.message}
            parsed.onSuccess{files->
                if(files.isEmpty()){
                    status.text="No direct media files are loaded. Scroll posts, sign in if required, and scan again."
                }else{
                    status.text=files.size.toString()+" media links found · queueing accessible files…"
                    kotlin.concurrent.thread(name="asterion-visible-media"){ 
                        val result=com.azrael.pixivdumpsync.VisibleMediaSupport.queue(
                            applicationContext,page,files)
                        runOnUiThread{
                            if(!isFinishing && !isDestroyed)status.text=
                                "${result.queued} queued · ${result.already} already saved · "+
                                "${result.failed} unavailable/invalid. Provider authorization still applies."
                        }
                    }
                }
            }
        }
    }

    private fun providerDownloadListener() = DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
        val folder=intent.getStringExtra(EXTRA_FOLDER).orEmpty()
        if(url.startsWith("magnet:?xt=urn:btih:",true) ||
            (url.startsWith("https://",true) && runCatching{
                URI(url).path.endsWith(".torrent",true)
            }.getOrDefault(false))){
            ResourceDownloads.route(this,url,folder.ifBlank{"Files"})
            return@DownloadListener
        }
        runCatching {
            val uri = URI(url)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
                "An accessible HTTPS original file URL is required"
            }
            val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val route = intent.getStringExtra(EXTRA_FOLDER)
                ?.takeIf { it in setOf("Artwork","Cosplay","Videos","Comics",
                    "Books","Games","Assets","References",
                    "Files","Audio","Adult","Creators") }
            if(route=="Books" && !filename.endsWith(".epub",true))
                throw IllegalArgumentException("Books only accepts EPUB originals")
            if(route=="Comics" && !filename.endsWith(".zip",true))
                throw IllegalArgumentException("Comics only accepts ZIP archives")
            if(route=="Games" && Regex("(?i)\\.(?:mp4|mkv|webm|mov)$")
                    .containsMatchIn(filename))
                throw IllegalArgumentException("Games must contain game files, not videos")
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(filename)
                setMimeType(mimeType)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                addRequestHeader("User-Agent", userAgent ?: HttpClient.USER_AGENT)
                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                    addRequestHeader("Cookie", it)
                }
                ResourceDownloads.privacyReferer(url,webView.url.orEmpty())?.let {
                    addRequestHeader("Referer", it)
                }
                val group=route?:"Files"
                val host=uri.host.orEmpty()
                    .replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
                val directory="$group/$host"
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                    "AsterionDownloader/$directory/"+safeFileName(filename))
            }
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id=manager.enqueue(request)
            val category=route?:"Files"
            val host=uri.host.orEmpty()
                .replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
            com.azrael.pixivdumpsync.DirectDownloadTracker.record(
                this,id,safeFileName(filename),category,url)
            com.azrael.pixivdumpsync.DirectDownloadRetry.remember(
                this,id,url,"$category/$host",safeFileName(filename),webView.url)
        }.onSuccess {
            Toast.makeText(
                this,
                "Original download queued in Android Downloads",
                Toast.LENGTH_LONG
            ).show()
        }.onFailure {
            Toast.makeText(this, it.message ?: "Could not queue provider download", Toast.LENGTH_LONG).show()
        }
    }

    private fun safeFileName(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .take(160)
        .ifBlank { "download.bin" }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_FOLDER = "aloha_section_folder"
    }
}
