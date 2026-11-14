package com.azrael.pixivdumpsync

import android.app.Activity
import android.app.DownloadManager
import android.os.Bundle
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.URI
import java.security.MessageDigest

/**
 * Gumroad original-file download browser. A real HTTPS attachment exposed by the
 * current page is sent to Android DownloadManager with same-host session cookies.
 * No purchase, paid content, DRM, or absent file is unlocked by this activity.
 */
class AssetMarketplaceActivity:Activity(){
    companion object {
        const val EXTRA_URL="asterion_marketplace_url"
        private const val HOME="https://gumroad.com/discover?query=3d%20model"
        private fun trustedGumroad(url:String):Boolean=runCatching{
            val u=URI(url)
            val host=u.host?.lowercase().orEmpty()
            u.scheme=="https" &&
                (host=="gumroad.com" || host.endsWith(".gumroad.com"))
        }.getOrDefault(false)
        internal fun safeDownloadUrl(url:String):Boolean=runCatching{
            val u=URI(url)
            val host=u.host?.lowercase().orEmpty()
            if(u.scheme!="https" || host.isEmpty() ||
                host=="localhost" || host.endsWith(".local") ||
                host.contains(":") || u.userInfo!=null)return@runCatching false
            if(Regex("^[0-9.]+$").matches(host)){
                val p=host.split('.').mapNotNull{it.toIntOrNull()}
                if(p.size!=4 || p.any{it>255})return@runCatching false
                if(p[0] in listOf(0,10,127) ||
                    p[0]==192&&p[1]==168 || p[0]==172&&p[1] in 16..31 ||
                    p[0]==169&&p[1]==254)return@runCatching false
            }
            true
        }.getOrDefault(false)
    }
    private lateinit var browser:WebView
    private lateinit var status:TextView
    private val session=System.currentTimeMillis().toString()
    private fun warn(message:String){
        status.text=message
        Toast.makeText(this,message,Toast.LENGTH_LONG).show()
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val ui=NocturneDesign
        val body=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(ui.background)
            setPadding(ui.px(this@AssetMarketplaceActivity,8),ui.px(this@AssetMarketplaceActivity,7),
                ui.px(this@AssetMarketplaceActivity,8),ui.px(this@AssetMarketplaceActivity,7))
        }
        body.addView(ui.text(this,"ASSETS · GUMROAD ORIGINALS",20f,ui.white,true),ui.lp(this,5))
        status=ui.text(this,"Browse products or sign in. Tap an accessible original file to download it.",
            12f,ui.secondary)
        body.addView(status,ui.lp(this,7))
        val address=EditText(this).apply{
            hint="Product URL, artist or model name"
            setSingleLine(true);setTextColor(ui.white);setHintTextColor(ui.secondary)
        }
        body.addView(address,ui.lp(this,4))
        body.addView(ui.action(this,"OPEN PRODUCT / SEARCH ASSETS",true){
            val query=address.text.toString().trim()
            val target=if(query.startsWith("https://"))query
                else "https://gumroad.com/discover?query="+Uri.encode(query.ifBlank{"3d model"})
            if(trustedGumroad(target))browser.loadUrl(target)
            else warn("Only secure Gumroad product/store pages can open here")
        },ui.lp(this,5))
        browser=WebView(this).apply{
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=true
            settings.allowFileAccess=false
            settings.allowContentAccess=false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this,true)
            webViewClient=object:WebViewClient(){
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean{
                    val url=request.url.toString()
                    if(trustedGumroad(url))return false
                    // Keep unrelated websites outside the credentialed Gumroad browser.
                    if(safeDownloadUrl(url) &&
                        (url.endsWith(".zip",true) || url.endsWith(".blend",true) ||
                         url.endsWith(".fbx",true) || url.endsWith(".7z",true))){
                        enqueue(url,null,null,view.url)
                        return true
                    }
                    if(url.startsWith("https://"))runCatching{
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,Uri.parse(url)))
                    }
                    return true
                }
                override fun onPageFinished(view:WebView,url:String){
                    super.onPageFinished(view,url)
                    status.text="Gumroad · tap the product's accessible original download"
                }
            }
            setDownloadListener{url,userAgent,disposition,mime,length->
                enqueue(url,disposition,mime,this.url)
            }
        }
        body.addView(browser,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(body)
        val launch=intent.getStringExtra(EXTRA_URL).orEmpty()
        browser.loadUrl(if(trustedGumroad(launch))launch else HOME)
    }
    private fun enqueue(url:String,disposition:String?,mime:String?,referer:String?){
        if(!safeDownloadUrl(url)){warn("Refused an unsafe original-file URL");return}
        val name=URLUtil.guessFileName(url,disposition,mime)
            .substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^\\p{L}\\p{N}._ +()-]"),"_")
            .take(120).ifBlank{"original_asset"}
        val token=MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString(""){"%02x".format(it)}
        val prefs=getSharedPreferences("asterion_marketplace_downloads",Context.MODE_PRIVATE)
        val dm=getSystemService(DownloadManager::class.java)
        val old=prefs.getLong(token,-1L)
        if(old>0L){
            val isActive=runCatching{
                dm.query(DownloadManager.Query().setFilterById(old))?.use{c->
                    if(!c.moveToFirst())false else {
                        val state=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        state in listOf(DownloadManager.STATUS_PENDING,DownloadManager.STATUS_RUNNING,
                            DownloadManager.STATUS_PAUSED,DownloadManager.STATUS_SUCCESSFUL)
                    }
                }==true
            }.getOrDefault(false)
            if(isActive){warn("Already queued or saved: "+name);return}
        }
        val request=DownloadManager.Request(Uri.parse(url)).apply{
            setTitle(name)
            setDescription("Gumroad accessible original · Asterion Assets")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            val cookies=CookieManager.getInstance().getCookie(url)
            if(!cookies.isNullOrBlank())addRequestHeader("Cookie",cookies)
            val source=referer?.takeIf{trustedGumroad(it)}
            if(source!=null)addRequestHeader("Referer",source)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                "AsterionDownloader/Assets/Gumroad/"+session+"/"+name)
        }
        runCatching{dm.enqueue(request)}
            .onSuccess{id->
                prefs.edit().putLong(token,id).apply()
                warn("Original file queued: "+name+" · check Android Downloads")
            }.onFailure{warn("Original file could not be queued: "+it.message)}
    }
    override fun onDestroy(){
        if(::browser.isInitialized){browser.stopLoading();browser.destroy()}
        super.onDestroy()
    }
}
