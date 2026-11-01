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
    private val observed=linkedSetOf<String>()
    private var queued=0
    private var auto=false
    private var passes=0
    private val handler=Handler(Looper.getMainLooper())
    private val runner=object:Runnable{
        override fun run(){
            if(!auto || passes>=120){auto=false;update();return}
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
        if(listUrl.isBlank() && !handle.matches(Regex("[A-Za-z0-9_]{1,15}"))){
            finish();return
        }
        val ui=NocturneDesign
        val outer=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(ui.background)
            setPadding(ui.px(this@XMediaCaptureActivity,10),ui.px(this@XMediaCaptureActivity,10),
                ui.px(this@XMediaCaptureActivity,10),ui.px(this@XMediaCaptureActivity,10))
        }
        outer.addView(ui.text(this,if(listUrl.isBlank())"X · @"+handle+" media"
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
            webViewClient=WebViewClient()
        }
        outer.addView(browser,LinearLayout.LayoutParams(-1,0,1f))
        outer.addView(ui.action(this,"CAPTURE VISIBLE PHOTOS",true){capture()},ui.lp(this,7))
        outer.addView(ui.action(this,"DOWNLOAD CAPTURED PHOTOS",false){
            queueObserved()
            XPhotoEngine.start(this)
            update()
        },ui.lp(this,6))
        outer.addView(ui.action(this,"AUTO-SCROLL LIST & DOWNLOAD",true){
            if(listUrl.isBlank()){
                Toast.makeText(this,"Auto collection is for a selected X list",Toast.LENGTH_LONG).show()
            }else{
                auto=true;passes=0;handler.removeCallbacks(runner);handler.post(runner)
                update()
            }
        },ui.lp(this,6))
        outer.addView(ui.action(this,"STOP AUTO COLLECTION",false){
            auto=false;handler.removeCallbacks(runner);queueObserved()
            XPhotoEngine.start(this);update()
        },ui.lp(this,6))
        setContentView(outer)
        browser.loadUrl(if(listUrl.isBlank())"https://x.com/"+handle+"/media" else listUrl)
    }
    private fun capture(){
        browser.evaluateJavascript("""(function(){
          const found=new Set();
          document.querySelectorAll('article img[src*="pbs.twimg.com/media/"], [data-testid="tweetPhoto"] img').forEach(img=>{
            const u=img.getAttribute('src')||'';
            if(u.startsWith('https://pbs.twimg.com/media/'))
              found.add(u.replace(/([?&])name=[^&]*/,'$1name=large'));
          });
          AsterionMedia.found(JSON.stringify(Array.from(found)));
          return found.size;
        })()""",null)
    }
    private fun queueObserved(){
        if(observed.isEmpty())return
        val src=if(listUrl.isBlank())"https://x.com/"+handle else listUrl
        XPhotoQueue(this).use{db->
            for(url in observed)
                if(db.addPhoto(src,url.hashCode().toString(),url))queued++
        }
    }
    private fun update(){
        status.text=observed.size.toString()+" photos found · "+queued+
            " queued · "+(if(auto)"auto-collect "+passes+"/120" else "idle")+
            ". Results are saved to Downloads/AsterionDownloader/X."
    }
    inner class Bridge{
        @JavascriptInterface fun found(json:String){
            val links=runCatching{
                val arr=JSONArray(json)
                (0 until minOf(300,arr.length())).map{arr.optString(it)}
            }.getOrDefault(emptyList())
            runOnUiThread{
                links.filter{it.startsWith("https://pbs.twimg.com/media/")}.forEach{observed.add(it)}
                if(auto){
                    queueObserved()
                    XPhotoEngine.start(this@XMediaCaptureActivity)
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
