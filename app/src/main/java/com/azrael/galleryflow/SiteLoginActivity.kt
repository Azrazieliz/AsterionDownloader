package com.azrael.galleryflow

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.azrael.pixivdumpsync.NocturneDesign
import com.azrael.pixivdumpsync.SearchRanking

/**
 * A source-owned browser session. No passwords or raw cookies are copied into
 * settings or app databases; the WebView cookie jar owns its session data.
 */
object SiteSession {
    private const val PREFS="asterion_site_connections"
    fun validLoginUrl(url:String):Boolean =
        SearchRanking.validUrl(url) && Uri.parse(url).scheme.equals("https",true)
    fun cookie(url:String):String? {
        if(!validLoginUrl(url))return null
        return runCatching{CookieManager.getInstance().getCookie(url)}
            .getOrNull()?.takeIf{it.isNotBlank()}
    }
    /**
     * Prefer the owner's main-process cookie jar. Android requires isolated
     * WebView directories for VideoFlow's two services, so calling the local
     * CookieManager from those services would silently lose the login.
     */
    fun cookie(context:Context,url:String):String? {
        if(!validLoginUrl(url))return null
        val process=if(android.os.Build.VERSION.SDK_INT>=28)
            android.app.Application.getProcessName() else context.packageName
        if(process==context.packageName)return cookie(url)
        if(!bound(context,url))return null
        val fromMain=runCatching {
            val provider=Uri.parse("content://"+context.packageName+".session-cookies")
            context.contentResolver.call(
                provider,"read_site_cookie",url,null)?.getString("cookie")
        }.getOrNull()
        return fromMain?.takeIf{it.isNotBlank()}?:cookie(url)
    }

    private fun host(url:String):String = Uri.parse(url).host.orEmpty()
        .lowercase(java.util.Locale.ROOT).removePrefix("www.")
    fun bound(context:Context,url:String):Boolean{
        if(!validLoginUrl(url))return false
        // SharedPreferences are not synchronously coherent across Android
        // processes. Ask the main process, which owns the user's connection,
        // rather than rejecting a legitimate session from a stale agent cache.
        if(android.os.Build.VERSION.SDK_INT>=28 &&
            android.app.Application.getProcessName()!=context.packageName){
            return runCatching {
                val authority=Uri.parse(
                    "content://"+context.packageName+".session-cookies")
                context.contentResolver.call(
                    authority,"is_site_connected",url,null)
                    ?.getBoolean("connected")==true
            }.getOrDefault(false)
        }
        val key=host(url)
        if(!context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
            .getBoolean(key,false))return false
        return if(key=="x.com"||key=="twitter.com")
            cookie(url)?.split(';')?.any{it.trim().startsWith("auth_token=")}==true
        else true // Accessible browser session saved; cookies are optional.
    }
    fun save(context:Context,url:String){
        require(validLoginUrl(url))
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .putBoolean(host(url),true).apply()
    }
    fun disconnect(context:Context,url:String) {
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .remove(host(url)).apply()
    }
}

class SiteLoginActivity:Activity() {
    companion object { const val EXTRA_URL="site_login_url" }
    private var browser:WebView?=null
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        val address=intent.getStringExtra(EXTRA_URL).orEmpty()
        if(!SiteSession.validLoginUrl(address)){
            Toast.makeText(this,"Choose a valid HTTPS site to sign in",Toast.LENGTH_LONG).show()
            finish();return
        }
        val ui=NocturneDesign
        val shell=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(ui.background)
        }
        val title=ui.text(this,"Connect website account",19f,ui.white,true)
        title.setPadding(ui.px(this,15),ui.px(this,14),ui.px(this,15),ui.px(this,4))
        shell.addView(title)
        shell.addView(ui.text(this,
            "Log in on the website. Cookies stay in Android's browser storage; sign-in is not shared with other sites.",
            12f,ui.secondary).apply{
            setPadding(ui.px(this@SiteLoginActivity,15),0,
                ui.px(this@SiteLoginActivity,15),ui.px(this@SiteLoginActivity,10))
        })
        val web=WebView(this)
        browser=web
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false)
        web.settings.javaScriptEnabled=true
        web.settings.domStorageEnabled=true
        web.settings.allowFileAccess=false
        web.settings.allowContentAccess=false
        web.webViewClient=WebViewClient()
        web.webChromeClient=WebChromeClient()
        shell.addView(web,LinearLayout.LayoutParams(-1,0,1f))
        val actions=ui.row(this)
        actions.addView(ui.action(this,"BACK"){
            if(web.canGoBack())web.goBack() else finish()
        },LinearLayout.LayoutParams(0,-2,1f))
        actions.addView(ui.action(this,"SAVE CONNECTION",true){
            val xSite=Uri.parse(address).host.orEmpty().removePrefix("www.") in
                setOf("x.com","twitter.com")
            web.evaluateJavascript("""(function(){
                const title=document.title||'';
                const body=(document.body?.innerText||'').slice(0,800);
                const good=document.readyState==='complete' &&
                    !/just a moment|attention required|verify you are human|enable javascript and cookies|access denied|403 forbidden|rate limit/i.test(title+' '+body);
                const watchLinks=document.querySelectorAll(
                    'a[href*="/watch/"],video[src],video source[src]').length;
                // Do not mark a saved site as accessible merely because its
                // Cloudflare/403 shell returned HTTP 200. The actual publisher
                // must expose real first-party cards or a selected watch page.
                const sameHost=u=>{try{
                    return new URL(u,location.href).hostname.replace(/^www\\./,'')===
                        location.hostname.replace(/^www\\./,'');
                }catch(_){return false;}};
                const links=[...document.querySelectorAll('a[href]')]
                    .filter(el=>sameHost(el.href))
                    .map(el=>new URL(el.href,location.href).pathname);
                const javPosts=links.filter(p=>
                    /^\\/[0-9]{4,10}\\/[a-z0-9_-]{4,240}\\/?$/i.test(p)).length;
                const torrentCards=links.filter(p=>
                    /^\\/torrent\\/[a-z0-9_-]{2,100}\\/?$/i.test(p)).length;
                const isSelectedJavPost=
                    /^\\/[0-9]{4,10}\\/[a-z0-9_-]{4,240}\\/?$/i.test(location.pathname);
                return JSON.stringify({good:good,host:location.hostname,
                    watchLinks:watchLinks,javPosts:javPosts,
                    torrentCards:torrentCards,isSelectedJavPost:isSelectedJavPost});
            })()"""){value->
                val info=runCatching {
                    org.json.JSONObject(org.json.JSONTokener(value).nextValue() as String)
                }.getOrNull()
                val currentHost=info?.optString("host").orEmpty()
                    .lowercase(java.util.Locale.ROOT).removePrefix("www.")
                val expectedHost=Uri.parse(address).host.orEmpty()
                    .lowercase(java.util.Locale.ROOT).removePrefix("www.")
                val pageOk=info?.optBoolean("good")==true && currentHost==expectedHost
                // Archivebate's empty age-gate shell previously passed this
                // test as a valid connection even with ZERO public videos.
                // A saved cookie alone is not proof that catalog data loaded.
                val archive=expectedHost=="archivebate.com"
                val jav=expectedHost=="jav.guru"
                val onejav=expectedHost=="onejav.com"
                val hasPublicVideos=info?.optInt("watchLinks",0)?:0
                val javAccess=(info?.optInt("javPosts",0)?:0)>0 ||
                    info?.optBoolean("isSelectedJavPost")==true
                val torrentAccess=(info?.optInt("torrentCards",0)?:0)>0
                val authenticated=pageOk &&
                    (!archive || hasPublicVideos>0) &&
                    (!jav || javAccess) && (!onejav || torrentAccess) &&
                    (!xSite || SiteSession.cookie(address)?.split(';')?.any{
                        it.trim().startsWith("auth_token=")}==true)
                if(authenticated){
                    CookieManager.getInstance().flush()
                    SiteSession.save(this,address)
                    // Resume only books paused because THIS website previously
                    // required login/interactive verification. Cached chapters
                    // survive and are never discarded by account switching.
                    val resumed=com.azrael.pixivdumpsync.NovelEpubQueue(this).use{
                        it.resumeForSite(address)
                    }
                    if(resumed>0)
                        com.azrael.pixivdumpsync.NovelEpubService.schedule(this)
                }
                Toast.makeText(this,if(authenticated)
                    "Browser session saved. Access to restricted media still depends on account entitlement."
                    else if(archive && pageOk)
                        "Archivebate loaded without any video cards. "+
                        "Use the site's own Continue control; no catalog access verified."
                    else if(jav && pageOk)
                        "Jav Guru did not expose a real video post. "+
                        "Complete the website's own verification before saving."
                    else if(onejav && pageOk)
                        "OneJAV exposed no torrent cards. Provider access is "+
                        "currently restricted; no account connection saved."
                    else "Website verification is incomplete. Connection not saved.",
                    Toast.LENGTH_LONG).show()
                if(authenticated){
                    setResult(RESULT_OK,Intent().putExtra(EXTRA_URL,address))
                    finish()
                }
            }
        },LinearLayout.LayoutParams(0,-2,1f))
        actions.setPadding(ui.px(this,12),ui.px(this,8),ui.px(this,12),ui.px(this,8))
        shell.addView(actions)
        setContentView(shell)
        web.loadUrl(address)
    }
    override fun onDestroy(){
        browser?.apply{stopLoading();destroy()}
        browser=null
        super.onDestroy()
    }
    @Suppress("DEPRECATION")
    override fun onBackPressed(){
        val w=browser
        if(w!=null&&w.canGoBack())w.goBack() else super.onBackPressed()
    }
}
