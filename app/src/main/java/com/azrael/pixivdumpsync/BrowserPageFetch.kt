package com.azrael.pixivdumpsync

import android.content.Context
import com.azrael.galleryflow.SiteSession
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
    // These approved publishers may expose public catalogs to ordinary
    // Android WebView while their direct HTML endpoint rejects non-browser
    // HTTP clients. Never solve access challenges or bypass authentication.
    private val publicBrowserHosts=setOf("jav.guru","rule34video.com",
        "kiutaku.com","freewebnovel.com","novelbuddy.me",
        "novelbuddy.com","readnovelfull.com",
        "hentaibros.net","3dhentaix.com")
    fun publicEligible(url:String):Boolean {
        val uri=runCatching{java.net.URI(url)}.getOrNull()?:return false
        val host=uri.host.orEmpty().lowercase().removePrefix("www.")
        return uri.scheme=="https" && uri.userInfo==null &&
            host in publicBrowserHosts
    }
    fun html(context:Context,url:String):String {
        com.asterion.videoflow.NetworkRoute.requireVpn(context,url)
        require(com.azrael.galleryflow.SiteSession.bound(context,url) ||
            publicEligible(url)) {
            "Save an accessible website session before retrying."
        }
        check(Looper.myLooper()!=Looper.getMainLooper()) {
            "WebView retrieval must run on a background thread."
        }
        // VideoFlow's two isolated services cannot see the WebView login
        // stored in the main process. Ask our non-exported provider to perform
        // the authorized DOM read in the actual owner process.
        if(android.os.Build.VERSION.SDK_INT>=28 &&
            android.app.Application.getProcessName()!=context.packageName &&
            com.azrael.galleryflow.SiteSession.bound(context,url)){
            val response=context.contentResolver.call(
                Uri.parse("content://"+context.packageName+".session-cookies"),
                "read_site_html",url,null)
            val html=response?.getString("html")
            if(html.isNullOrBlank())throw IllegalStateException(
                response?.getString("error")?:
                    "Connected provider browser could not supply accessible content")
            return html
        }
        val host=Uri.parse(url).host.orEmpty().lowercase().removePrefix("www.")
        val result=AtomicReference<String?>()
        val error=AtomicReference<String?>()
        val latch=CountDownLatch(1)
        val main=Handler(Looper.getMainLooper())
        val instance=AtomicReference<WebView?>()
        // The user has explicitly connected this site. Archivebate's normal
        // public Continue step populates watch entries asynchronously without
        // navigating to a new URL. Do not repeat the click or auto-accept a
        // gateway for a source that the user has not connected.
        var archivebateContinued=false
        var archivebateWaits=0
        main.post {
            try{
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
                    if(host=="archivebate.com" &&
                        SiteSession.bound(context,url) && !archivebateContinued){
                        archivebateContinued=true
                        main.postDelayed({
                            if(latch.count==0L)return@postDelayed
                            v.evaluateJavascript("""(function(){
                                const action=[...document.querySelectorAll(
                                    'button,a,[role="button"]')]
                                    .find(el=>/^continue$/i.test(
                                        (el.innerText||el.textContent||'').trim()));
                                if(!action)return false;
                                action.click();
                                return true;
                            })()"""){clicked->
                                // Reinspect the actual DOM even if the public
                                // gate was absent. No fake cards or media URLs.
                                main.postDelayed({
                                    if(latch.count>0L)onPageFinished(v,finished)
                                },if(clicked=="true")1200L else 0L)
                            }
                        },400L)
                        return
                    }
                    main.postDelayed({
                        if(latch.count==0L)return@postDelayed
                        try{
                        v.evaluateJavascript("""(function(){
                            const title=document.title||'';
                            const body=(document.body?.innerText||'').slice(0,800);
                            if(/just a moment|attention required|verify you are human|enable javascript and cookies/i.test(title+' '+body))return '';
                            const esc=s=>String(s||'').replace(/[&<>"']/g,c=>({
                                '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
                            const anchors=[...document.querySelectorAll('a[href],option[value]')]
                                .slice(0,10000).map(el=>{
                                    const img=el.querySelector('img');
                                    const poster=el.querySelector('video[poster]');
                                    const keys=['src','data-src','data-original','data-lazy-src',
                                        'data-cfsrc','data-lazy','data-full','data-full-src',
                                        'data-highres','srcset'];
                                    const cover=img?'<img '+keys.map(k=>k+'="'+
                                        esc(img.getAttribute(k)||'')+'"').join(' ')+'/>':
                                        poster?'<img src="'+esc(poster.getAttribute('poster'))+'"/>':'';
                                    const link='<a href="'+esc(el.getAttribute('href')||
                                        el.getAttribute('value')||'')+'" id="'+esc(el.id||'')+
                                        '" class="'+esc(el.className||'')+'" rel="'+
                                        esc(el.getAttribute('rel')||'')+'" title="'+
                                        esc(el.getAttribute('title')||'')+'">'+cover+
                                        esc((el.innerText||el.textContent||'').slice(0,220))+'</a>';
                                    // Preserve enough original DOM context for
                                    // native provider-specific gallery selectors.
                                    const tile=el.closest('article,.post-card,.video-item,'+
                                        '.gallery-item,.post-item,.media-item,.card');
                                    // Keep real publisher headings for provider-specific
                                    // article selectors (including Jav Guru).
                                    const heading=el.closest('h1,h2,h3,h4');
                                    const item=heading?'<h2>'+link+'</h2>':link;
                                    return tile?'<article class="'+esc(tile.className||'')+
                                        '">'+item+'</article>':item;
                                }).join('');
                            const reader=document.querySelectorAll(
                                '.reading-content img,.chapter-content img,#reader img,'+
                                '.page-chapter img,.container-chapter-reader img,'+
                                '#readerarea img,.chapter-images img,img.wp-manga-chapter-img');
                            const images=[...(reader.length?reader:
                                document.querySelectorAll('main img,article img,img[data-src]'))]
                                .slice(0,2500).map(el=>{
                                const keys=['src','data-src','data-original','data-lazy-src',
                                    'data-cfsrc','data-lazy','data-full','data-full-src',
                                    'data-highres','srcset'];
                                return '<img class="'+esc(el.className||'')+'" '+keys.map(k=>
                                    k+'="'+esc(el.getAttribute(k)||'')+'"').join(' ')+'/>';
                            }).join('');
                            // Preserve only video URLs genuinely present in
                            // the user's authenticated browser DOM.
                            // CurrentSrc is populated by JS/video.js players after the
                            // initial document load; HTML attributes alone were
                            // empty on the real HentaiBros and 3DHentaiX pages.
                            // Do NOT claim short trailers as full originals:
                            // preserve duration for the receiving resolver to
                            // reject clips below the selected-video threshold.
                            const players=[...document.querySelectorAll(
                                'main video,article video,.video-player video,'+
                                '#player video,.video-js video,video source[src]')]
                                .map(el=>el.tagName.toLowerCase()==='video'?
                                    el:el.closest('video')).filter((el,i,list)=>
                                    !!el && list.indexOf(el)===i &&
                                    !el.closest('.related,.recommendations,'+
                                        '.happy-under-player,.ads,.advertisement'))
                                .slice(0,40).map(video=>{
                                    const src=video.currentSrc||video.getAttribute('src')||
                                        video.querySelector('source[src]')?.src||'';
                                    const duration=Number.isFinite(video.duration)?
                                        Math.round(video.duration):0;
                                    return '<video src="'+esc(src)+'" data-duration="'+
                                        duration+'" poster="'+
                                        esc(video.getAttribute('poster')||'')+'"></video>';
                                }).join('');
                            // Keep only the exact selected publisher's
                            // documented player iframe. Never serialize ad,
                            // social/comment or recommendation iframe URLs.
                            const publisher=location.hostname.replace(/^www\\./,'');
                            const currentPath=location.pathname||'';
                            const embeds=[...document.querySelectorAll(
                                'iframe[src]')].filter(el=>{
                                let u;
                                try{u=new URL(el.getAttribute('src'),location.href);}
                                catch(_){return false;}
                                if(u.protocol!=='https:')return false;
                                const host=u.hostname.replace(/^www\\./,'');
                                if(publisher==='archivebate.com')
                                    return /^\\/watch\\/\\d{1,16}\\/?$/.test(currentPath) &&
                                        host==='mixdrop.ag' &&
                                        /^\\/e\\/[A-Za-z0-9_-]{5,64}\\/?$/.test(u.pathname);
                                if(publisher==='uncensoredhentai.xxx')
                                    return /^\\/(?:watch|video)\\//.test(currentPath) &&
                                        host==='nhplayer.com' &&
                                        /^\\/v\\/[A-Za-z0-9]{8,40}\\/?$/.test(u.pathname);
                                if(publisher==='fullporner.com')
                                    return /^\\/watch\\/[a-f0-9]{24}\\/?$/i.test(currentPath) &&
                                        host==='fullporner.com' &&
                                        /^\\/player\\/\\d{1,12}\\/\\d{1,4}\\/?$/.test(u.pathname);
                                if(['jav.guru','javideo.net','mp4hentai.com',
                                    'onlyhentaistuff.com','hentaihaven.xxx']
                                    .includes(publisher))
                                    return host===publisher &&
                                        /^\\/(?:embed|player|watch|video)\\//.test(u.pathname);
                                return false;
                            }).slice(0,5).map(el=>
                                '<iframe src="'+esc(new URL(
                                    el.getAttribute('src'),location.href).href)+
                                '" allowfullscreen></iframe>').join('');
                            const chapter=document.querySelector(
                                '#article,#chapter-content,.chapter-content,'+
                                '#chapterContent,.reading-content,.read-content,'+
                                '.novel-content,#chapter-body,#readerarea');
                            const chapterTitle=chapter?.querySelector('h1,h2,h3,h4')?.innerText||
                                document.querySelector('h1,h2,h3,h4')?.innerText||'';
                            const paragraphs=chapter?[...chapter.querySelectorAll('p')]
                                .slice(0,1000).map(p=>'<p>'+
                                    esc((p.innerText||p.textContent||'').slice(0,20000))+
                                    '</p>').join(''):'';
                            const realH1=document.querySelector('h1')?.innerText||'';
                            return '<html><head><title>'+esc(title)+
                                '</title></head><body><h1>'+esc(realH1)+
                                '</h1><h4>'+esc(chapterTitle)+
                                '</h4>'+anchors+'<main><div id="article">'+
                                paragraphs+'</div><div class="video-player">'+players+
                                embeds+'</div><div id="reader">'+images+
                                '</div></main></body></html>';
                        })()"""){raw->
                            val page=runCatching{JSONTokener(raw).nextValue() as? String}
                                .getOrNull()
                            if(page.isNullOrBlank()){
                                error.set("The browser still shows a website verification page.")
                                latch.countDown()
                            }else if(host=="archivebate.com" &&
                                SiteSession.bound(context,url) &&
                                Uri.parse(url).path.orEmpty().trim('/')=="" &&
                                !page.contains("href=\"/watch/") &&
                                archivebateWaits++<6){
                                // The public Continue step sometimes populates
                                // its real /watch/ listing asynchronously.
                                // Wait for observed cards; do NOT invent entries
                                // or click the site's verification controls.
                                main.postDelayed({
                                    if(latch.count>0L)onPageFinished(v,finished)
                                },1_500L)
                            }else{
                                result.set(page)
                                latch.countDown()
                            }
                        }
                        }catch(t:Throwable){
                            error.set("WebView script failed: "+t.javaClass.simpleName)
                            latch.countDown()
                        }
                    },1800L)
                }
            }
            web.loadUrl(url)
            }catch(t:Throwable){
                error.set("WebView initialization failed: "+t.javaClass.simpleName)
                latch.countDown()
            }
        }
        val completed=latch.await(45,TimeUnit.SECONDS)
        main.post{runCatching{
            instance.getAndSet(null)?.apply{stopLoading();destroy()}
        }}
        if(!completed)throw IllegalStateException("Browser page retrieval timed out: "+url)
        return result.get()?:throw IllegalStateException(
            error.get()?:"Browser could not load this chapter.")
    }
}
