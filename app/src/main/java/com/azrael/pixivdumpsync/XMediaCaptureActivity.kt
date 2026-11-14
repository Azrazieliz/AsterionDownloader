package com.azrael.pixivdumpsync

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray

/** Authenticated X media and specific-list downloader: never copies post videos. */
class XMediaCaptureActivity:Activity() {
    companion object {
        const val EXTRA_HANDLE="x_handle"
        const val EXTRA_LIST_URL="x_list_url"
        const val EXTRA_FEED="x_following_feed"
        internal fun normalizeList(value:String):String?{
            val id=Regex("^https://(?:www\\.)?(?:x\\.com|twitter\\.com)/i/lists/([0-9]{4,25})/?$")
                .matchEntire(value.trim())?.groupValues?.getOrNull(1)
            return if(id==null)null else "https://x.com/i/lists/"+id
        }
    }
    private lateinit var browser:WebView
    private lateinit var status:TextView
    private var handle=""
    private var listUrl=""
    private var feed=false
    private val observed=linkedSetOf<String>()
    private val pending=linkedSetOf<String>()
    private var queued=0
    private var auto=false
    private var passes=0
    private var idlePasses=0
    private var previousCount=0
    private val handler=Handler(Looper.getMainLooper())
    private val runner=object:Runnable{
        override fun run(){
            if(!auto || passes>=3000){auto=false;queueObserved();XPhotoEngine.start(this@XMediaCaptureActivity);update();return}
            if(observed.size>previousCount){idlePasses=0;previousCount=observed.size}
            else if(passes>20)idlePasses++
            if(idlePasses>=40){auto=false;queueObserved();XPhotoEngine.start(this@XMediaCaptureActivity);update();return}
            capture()
            browser.evaluateJavascript("window.scrollBy(0,Math.floor(window.innerHeight*0.8));",null)
            passes++
            handler.postDelayed(this,950L)
        }
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        handle=intent.getStringExtra(EXTRA_HANDLE).orEmpty()
        listUrl=normalizeList(intent.getStringExtra(EXTRA_LIST_URL).orEmpty()).orEmpty()
        feed=intent.getBooleanExtra(EXTRA_FEED,false)
        if(listUrl.isBlank() && !feed && !handle.matches(Regex("[A-Za-z0-9_]{1,15}"))){
            finish();return
        }
        val ui=NocturneDesign
        val outer=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(ui.background)
            setPadding(ui.px(this@XMediaCaptureActivity,10),ui.px(this@XMediaCaptureActivity,10),
                ui.px(this@XMediaCaptureActivity,10),ui.px(this@XMediaCaptureActivity,10))
        }
        outer.addView(ui.text(this,if(feed)"X · FOLLOWING FEED" else if(listUrl.isBlank())"X · @"+handle+" media"
            else "X · selected list",20f,ui.white,true))
        status=ui.text(this,"Log into X if needed; scroll or auto-collect visible photos.",13f,ui.secondary)
        outer.addView(status,ui.lp(this,8))
        browser=WebView(this).apply{
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=true
            settings.allowFileAccess=false
            settings.allowContentAccess=false
            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
            addJavascriptInterface(Bridge(),"AsterionMedia")
            webViewClient=object:WebViewClient(){
                override fun onPageFinished(view:WebView?,url:String?){
                    super.onPageFinished(view,url)
                    if(feed)view?.let{browser->browser.postDelayed({
                        browser.evaluateJavascript("""(function(){
                          const tabs=Array.from(document.querySelectorAll('[role="tab"]'));
                          const following=tabs.find(x=>x.textContent.trim().toLowerCase()==='following');
                          if(following)following.click();
                        })()""",null)
                    },1300)}
                }
            }
        }
        outer.addView(browser,LinearLayout.LayoutParams(-1,0,1f))
        outer.addView(ui.action(this,"CAPTURE VISIBLE PHOTOS",true){capture()},ui.lp(this,7))
        outer.addView(ui.action(this,"DOWNLOAD CAPTURED PHOTOS",false){
            queueObserved()
            XPhotoEngine.start(this)
            update()
        },ui.lp(this,6))
        outer.addView(ui.action(this,"AUTO-SCROLL LIST & DOWNLOAD",true){
            auto=true;passes=0;idlePasses=0;previousCount=observed.size
            handler.removeCallbacks(runner);handler.post(runner);update()
        },ui.lp(this,6))
        outer.addView(ui.action(this,"STOP AUTO COLLECTION",false){
            auto=false;handler.removeCallbacks(runner);queueObserved()
            XPhotoEngine.start(this);update()
        },ui.lp(this,6))
        setContentView(outer)
        browser.loadUrl(if(feed)"https://x.com/home" else if(listUrl.isBlank())"https://x.com/"+handle+"/media" else listUrl)
    }
    private fun capture(){
        browser.evaluateJavascript("""(function(){
          const found=new Set();
          document.querySelectorAll('article img[src*="pbs.twimg.com/media/"], [data-testid="tweetPhoto"] img').forEach(img=>{
            const u=img.getAttribute('src')||'';
            if(u.startsWith('https://pbs.twimg.com/media/'))
              found.add(u.match(/[?&]name=/) ? u.replace(/([?&])name=[^&]*/,'$1name=orig') : u+(u.includes('?')?'&':'?')+'name=orig');
          });
          AsterionMedia.found(JSON.stringify(Array.from(found)));
          return found.size;
        })()""",null)
    }
    private fun queueObserved(){
        if(pending.isEmpty())return
        val src=if(feed)"https://x.com/home" else if(listUrl.isBlank())"https://x.com/"+handle else listUrl
        XPhotoQueue(this).use{db->
            for(url in pending)
                if(db.addPhoto(src,url.hashCode().toString(),url))queued++
            pending.clear()
        }
    }
    private fun update(){
        status.text=observed.size.toString()+" photos found · "+queued+
            " queued · "+(if(auto)"auto-collect "+passes+"/3000" else "idle")+
            ". Results are saved to Downloads/AsterionDownloader/X."
    }
    inner class Bridge{
        @JavascriptInterface fun found(json:String){
            val links=runCatching{
                val arr=JSONArray(json)
                (0 until minOf(300,arr.length())).map{arr.optString(it)}
            }.getOrDefault(emptyList())
            runOnUiThread{
                links.filter{it.startsWith("https://pbs.twimg.com/media/")}.forEach{
                    if(observed.add(it))pending.add(it)
                }
                if(auto){
                    queueObserved()
                    if(passes%15==0)XPhotoEngine.start(this@XMediaCaptureActivity)
                }
                update()
            }
        }
    }
    override fun onDestroy(){
        auto=false;handler.removeCallbacks(runner)
        browser.removeJavascriptInterface("AsterionMedia")
        browser.destroy()
        super.onDestroy()
    }
}
