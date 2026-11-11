package com.azrael.pixivdumpsync

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Uses a verified browser session for accessible chapter pages when the
 * non-browser HTTP client is rejected. Never bypasses website verification. */
object BrowserPageFetch {
    fun html(context:Context,url:String):String {
        require(com.azrael.galleryflow.SiteSession.bound(context,url)) {
            "Save an accessible website session before retrying."
        }
        check(Looper.myLooper()!=Looper.getMainLooper()) {
            "WebView retrieval must run on a background thread."
        }
        val host=Uri.parse(url).host.orEmpty().lowercase().removePrefix("www.")
        val result=AtomicReference<String?>()
        val error=AtomicReference<String?>()
        val latch=CountDownLatch(1)
        val main=Handler(Looper.getMainLooper())
        val instance=AtomicReference<WebView?>()
        main.post {
            val web=WebView(context)
            instance.set(web)
            web.settings.javaScriptEnabled=true
            web.settings.domStorageEnabled=true
            web.settings.allowFileAccess=false
            web.settings.allowContentAccess=false
            CookieManager.getInstance().setAcceptCookie(true)
            web.webViewClient=object:WebViewClient(){
                override fun shouldOverrideUrlLoading(v:WebView,request:WebResourceRequest):Boolean {
                    return request.url.host.orEmpty().lowercase().removePrefix("www.")!=host
                }
                override fun onPageFinished(v:WebView,finished:String){
                    main.postDelayed({
                        if(latch.count==0L)return@postDelayed
                        v.evaluateJavascript("""(function(){
                            const title=document.title||'';
                            const body=(document.body?.innerText||'').slice(0,800);
                            if(/just a moment|attention required|verify you are human|enable javascript and cookies/i.test(title+' '+body))return '';
                            const esc=s=>String(s||'').replace(/[&<>"']/g,c=>({
                                '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
                            const anchors=[...document.querySelectorAll('a[href],option[value]')]
                                .slice(0,10000).map(el=>'<a href="'+esc(el.getAttribute('href')||
                                    el.getAttribute('value')||'')+'" class="'+esc(el.className||'')+
                                    '" rel="'+esc(el.getAttribute('rel')||'')+'">'+
                                    esc((el.innerText||el.textContent||'').slice(0,220))+'</a>').join('');
                            const reader=document.querySelectorAll(
                                '.reading-content img,.chapter-content img,#reader img,'+
                                '.page-chapter img,.container-chapter-reader img,'+
                                '#readerarea img,.chapter-images img,img.wp-manga-chapter-img');
                            const images=[...(reader.length?reader:
                                document.querySelectorAll('main img,article img,img[data-src]'))]
                                .slice(0,2500).map(el=>{
                                const keys=['src','data-src','data-original','data-lazy-src',
                                    'data-cfsrc','data-lazy','data-full','srcset'];
                                return '<img class="'+esc(el.className||'')+'" '+keys.map(k=>
                                    k+'="'+esc(el.getAttribute(k)||'')+'"').join(' ')+'/>';
                            }).join('');
                            return '<html><head><title>'+esc(title)+'</title></head><body>'+
                                anchors+'<div id="reader">'+images+'</div></body></html>';
                        })()"""){raw->
                            val page=runCatching{JSONTokener(raw).nextValue() as? String}
                                .getOrNull()
                            if(page.isNullOrBlank())
                                error.set("The browser still shows a website verification page.")
                            else result.set(page)
                            latch.countDown()
                        }
                    },1800L)
                }
            }
            web.loadUrl(url)
        }
        val completed=latch.await(45,TimeUnit.SECONDS)
        main.post{instance.getAndSet(null)?.apply{stopLoading();destroy()}}
        if(!completed)throw IllegalStateException("Browser page retrieval timed out: "+url)
        return result.get()?:throw IllegalStateException(
            error.get()?:"Browser could not load this chapter.")
    }
}
