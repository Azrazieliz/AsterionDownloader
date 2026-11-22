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
        "hentaibros.net","3dhentaix.com",
        // Exact first-party player/article pages. These are not generalized
        // Aloha-style media detectors, just the publishers' JS video players.
        "fullporner.com","myxstudio.top","mydaddy.cc","nhplayer.com",
        "archivebate.com",
        "voe.sx","kamehaus.net",
        "streamtape.com","streamtape.to","streamtape.net",
        "streamtape.site","sbtape.com",
        "hqpornerxxx.com","helloporn.com","javideo.net","play.javideo.net",
        "porneec.com","taiav.com","livecamrips.to",
        "hentaihaven.xxx","uncensoredhentai.xxx","mp4hentai.com","roshy.tv",
        "mirrored.to","upfiles.com","upfilesgo.com",
        // Owner-approved selected first-party video publishers. Their
        // live watch pages often expose only a JS player shell to HTTP.
        // Reading the existing authorized WebView DOM never authorizes an
        // ad iframe, bypasses a site gate or invents a media URL.
        "avple.tv","pimpbunny.com","xchina.co","en.xchina.co",
        "bubblecum.lol","pornavhd.com","cosplaytele.com",
        "notfans.com",
        // Public gallery rendering fallback only. No challenge solving.
        "4kgirl.com","4khd.com","m.4khd.com")
    fun publicEligible(url:String):Boolean {
        val uri=runCatching{java.net.URI(url)}.getOrNull()?:return false
        val host=uri.host.orEmpty().lowercase().removePrefix("www.")
        val selectedPornhub=host in setOf(
            "pornhub.com","m.pornhub.com","fr.pornhub.com","en.pornhub.com") &&
            uri.path=="/view_video.php" &&
            uri.rawQuery.orEmpty().split('&').count{
                it.startsWith("viewkey=") &&
                    it.removePrefix("viewkey=")
                        .matches(Regex("[A-Za-z0-9_-]{5,50}"))
            }==1
        return uri.scheme=="https" && uri.userInfo==null &&
            uri.port==-1 && (host in publicBrowserHosts || selectedPornhub)
    }
    fun html(context:Context,url:String,playerReferer:String?=null):String {
        com.asterion.videoflow.NetworkRoute.requireVpn(context,url)
        val attestedFrame=playerReferer?.let{
            com.asterion.videoflow.SiteSpecificOriginalSources
                .selectedExtractorFrame(it,url)
        }==true
        require(com.azrael.galleryflow.SiteSession.bound(context,url) ||
            publicEligible(url) || attestedFrame) {
            "Save an accessible website session before retrying."
        }
        check(Looper.myLooper()!=Looper.getMainLooper()) {
            "WebView retrieval must run on a background thread."
        }
        // VideoFlow's two isolated services cannot see the WebView login
        // stored in the main process. Ask our non-exported provider to perform
        // the authorized DOM read in the actual owner process.
        if(android.os.Build.VERSION.SDK_INT>=28 &&
            android.app.Application.getProcessName()!=context.packageName){
            // Public selected players must use the same browser/cookie owner
            // as connected pages. Rendering directly inside :videoflow_* can
            // allocate an empty independent WebView jar and leave the exact
            // JAVIDEO/NHPlayer stream invisible or rejected with HTTP 403.
            val response=context.contentResolver.call(
                Uri.parse("content://"+context.packageName+".session-cookies"),
                "read_site_html",url,android.os.Bundle().apply{
                    playerReferer?.takeIf{
                        publicEligible(it) ||
                            com.azrael.galleryflow.SiteSession.bound(context,it)
                    }?.let{putString("referer",it)}
                })
            val html=response?.getString("html")
                ?:response?.getString("html_gzip")?.let{
                    com.azrael.galleryflow.SessionHtmlEnvelope.expand(it)
                }
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
        var mirroredHostWaits=0
        var fourKhdBridgeWaits=0
        var mirrorSelectedClickAttempts=0
        // Mirrored.to exposes official file-host choices only AFTER its
        // selected /files/<id>/ 'View download links' control is clicked.
        // Restrict that ordinary first-party action to a user-selected
        // MP4Hentai article; never click unrelated Mirror listings or ads.
        val approvedMp4Mirror=host=="mirrored.to" &&
            Regex("""^/files/[A-Fa-f0-9]{8}/?$""")
                .matches(Uri.parse(url).path.orEmpty()) &&
            playerReferer?.let{origin->
                val site=Uri.parse(origin)
                site.scheme=="https" &&
                    site.host.orEmpty().removePrefix("www.")=="mp4hentai.com" &&
                    Regex("""^/20[0-9]{2}/(?:0[1-9]|1[0-2])/[^/?#]{3,300}/?$""")
                        .matches(site.path.orEmpty())
            }==true
        // Only the explicitly selected publishers with JS-loaded players.
        // This waits for the actual HTML5 media and metadata to appear;
        // it does not intercept arbitrary network requests or browse pages.
        var selectedPlayerWaits=0
        val selectedPlayerHost=host in setOf(
            "fullporner.com","mydaddy.cc","nhplayer.com","voe.sx","kamehaus.net",
            "hentaibros.net","3dhentaix.com","hentaihaven.xxx",
            "hqpornerxxx.com","javideo.net","play.javideo.net","helloporn.com","mp4hentai.com",
            "roshy.tv","rule34video.com","porneec.com","taiav.com","pornhub.com",
            "m.pornhub.com","fr.pornhub.com","en.pornhub.com",
            "streamtape.com","streamtape.to","streamtape.net",
            "streamtape.site","sbtape.com",
            "avple.tv","pimpbunny.com","xchina.co","en.xchina.co",
            "bubblecum.lol","pornavhd.com","cosplaytele.com","notfans.com",
            "video.playerrecord.cfd","vidara.to")
        val selectedPlayerPage=selectedPlayerHost && (
            com.asterion.videoflow.VideoSiteProfiles.isVideoPage(url) ||
            attestedFrame ||
            when(host) {
                "fullporner.com" -> Regex("""^/player/[0-9]{1,12}/[0-9]{1,4}/?$""")
                    .matches(Uri.parse(url).path.orEmpty())
                "mydaddy.cc" -> Regex("""^/video/[A-Za-z0-9_-]{8,160}/?$""")
                    .matches(Uri.parse(url).path.orEmpty())
                "nhplayer.com" ->
                    Regex("""^/v/[A-Za-z0-9]{8,40}/?$""")
                        .matches(Uri.parse(url).path.orEmpty()) ||
                    (Uri.parse(url).path=="/player.php" &&
                        runCatching {
                            Uri.parse(url).queryParameterNames==
                                setOf("i","s","vid") &&
                                listOf("i","s","vid").all {
                                    !Uri.parse(url).getQueryParameter(it).isNullOrBlank()
                                }
                        }.getOrDefault(false))
                "voe.sx","kamehaus.net" ->
                    Regex("""^/e/[A-Za-z0-9_-]{6,80}/?$""")
                        .matches(Uri.parse(url).path.orEmpty())
                "streamtape.com","streamtape.to","streamtape.net",
                "streamtape.site","sbtape.com" ->
                    Regex("""^/(?:e|v|embed)/[A-Za-z0-9_-]{5,100}/?$""")
                    .matches(Uri.parse(url).path.orEmpty())
                "play.javideo.net" ->
                    Regex("""^/(?:embed|player)/[A-Za-z0-9_-]{5,100}/?$""")
                        .matches(Uri.parse(url).path.orEmpty())
                // The same publisher can host its selected CosplayTele
                // article player at /embed/<id> or /player/<id>.
                // Do not treat gallery/category listings as video players.
                "cosplaytele.com" ->
                    Regex("""^/(?:embed|player)/[A-Za-z0-9_-]{5,100}/?$""")
                        .matches(Uri.parse(url).path.orEmpty())
                else -> false
            })
        main.post {
            if(latch.count==0L)return@post
            try{
            val web=WebView(context)
            instance.set(web)
            web.settings.javaScriptEnabled=true
            web.settings.domStorageEnabled=true
            if(selectedPlayerHost)web.settings.mediaPlaybackRequiresUserGesture=false
            web.settings.allowFileAccess=false
            web.settings.allowContentAccess=false
            CookieManager.getInstance().setAcceptCookie(true)
            web.webViewClient=object:WebViewClient(){
                override fun shouldOverrideUrlLoading(v:WebView,request:WebResourceRequest):Boolean {
                    // Cross-site iframe navigation was being blocked even for
                    // the exact player already attested by the selected post.
                    // Keep main-frame navigation on the publisher, but admit
                    // only approved selected player subframes (not ad popups).
                    return !SelectedVideoIframePolicy.allow(
                        url,request.url.toString(),request.isForMainFrame)
                }
                override fun onPageFinished(v:WebView,finished:String){
                    if(approvedMp4Mirror && mirrorSelectedClickAttempts<7){
                        mirrorSelectedClickAttempts++
                        main.postDelayed({
                            if(latch.count==0L)return@postDelayed
                            v.evaluateJavascript("""(function(){
                                if(!['mirrored.to','www.mirrored.to']
                                    .includes(location.hostname.toLowerCase()) ||
                                    !/^\/files\/[A-Fa-f0-9]{8}\/?$/
                                        .test(location.pathname))return false;
                                const control=document.querySelector('a#download-link');
                                if(!control || control.dataset.asterionClicked)
                                    return false;
                                try{
                                    const chosen=new URL(control.href);
                                    if(!['mirrored.to','www.mirrored.to']
                                            .includes(chosen.hostname.toLowerCase()) ||
                                        chosen.pathname!==location.pathname ||
                                        [...chosen.searchParams.keys()].join(',')!=='dl' ||
                                        chosen.searchParams.get('dl')!=='0')
                                        return false;
                                    control.dataset.asterionClicked='1';
                                    control.click();
                                    return true;
                                }catch(_){return false;}
                            })()"""){clicked->
                                if(clicked=="true")
                                    mirrorSelectedClickAttempts=7
                                main.postDelayed({
                                    if(latch.count>0L)onPageFinished(v,finished)
                                },if(clicked=="true")1400L else 550L)
                            }
                        },350L)
                        return
                    }
                    if(host=="archivebate.com" &&
                        Uri.parse(url).path.orEmpty().trim('/')=="" &&
                        (SiteSession.bound(context,url) || publicEligible(url)) &&
                        !archivebateContinued){
                        archivebateContinued=true
                        main.postDelayed({
                            if(latch.count==0L)return@postDelayed
                            v.evaluateJavascript("""(function(){
                                if(!['archivebate.com','www.archivebate.com']
                                    .includes(location.hostname.toLowerCase()) ||
                                   !['/',''].includes(location.pathname))return false;
                                const action=[...document.querySelectorAll(
                                    'button,a,[role="button"]')]
                                    .filter(el=>!el.closest(
                                        'aside,.ads,.advertisement,.sidebar,.related,[data-ad]'))
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
                            const mirroredPending=
                                location.hostname.replace(/^www\./,'')==='mirrored.to' &&
                                /fetching hosting links/i.test(document.body?.innerText||'');
                            const esc=s=>String(s||'').replace(/[&<>"']/g,c=>({
                                '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
                            // Rule34Video populates its first-party quality anchors only after
            // the selected video's Download button reveals the format dialog.
            // Never click a download hyperlink, unrelated card or ad.
            if(location.hostname.replace(/^www\./,'')==='rule34video.com' &&
                /^\/videos?\/\d{1,12}(?:\/|$)/.test(location.pathname)){
                const actions=[...document.querySelectorAll(
                        'button,[role="button"],a[href]')]
                    .filter(el=>{
                        if(el.closest('aside,.related,.recommended,'+
                                '.recommendations,.ads,.advertisement,.sidebar'))
                            return false;
                        if(el.tagName.toLowerCase()!=='a')return true;
                        const href=(el.getAttribute('href')||'').trim().toLowerCase();
                        // Open only an in-page modal; actual /get_file links
                        // are extracted as URLs and never clicked/downloaded.
                        return !href || href.startsWith('#') ||
                            href.startsWith('javascript:void');
                    });
                const action=actions.find(el=>/^(?:download|download video)$/i
                    .test((el.innerText||el.textContent||'').trim()));
                if(action && !action.dataset.asterionDownloadOpened){
                    action.dataset.asterionDownloadOpened='true';
                    action.click();
                }
            }
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
                            // FullPorner's exact /player/<video>/<layout> JS
                            // initializes fluidPlayer around #flvv. Request
                            // the selected element's normal HTML5 source,
                            // never intercept unrelated browser requests.
                            const selectedPlayerHost=location.hostname.replace(/^www\./,'');
                            // The owner-selected UncensoredHentai NHPlayer
                            // /v/ page publishes exactly one /player.php
                            // server (keys i,s,vid). The ordinary first-party
                            // AJAX get-video-url-v2.php HTTP 200 publishes
                            // window.videoReady before any HTML video tag.
                            // Serialize that verified selected JS source
                            // ONLY for its exact server and observed MP4 CDN.
                            // Never infer, solve or inject any challenge token.
                            // The /v/ page is a selected-server chooser, NOT the
                            // media player. Preserve only its single publisher-
                            // attested /player.php?i&s&vid entry in the compact
                            // browser snapshot. Otherwise Android loses the
                            // exact selected server whenever static HTTP is
                            // challenged, and falls back to unsupported yt-dlp.
                            const nhPlayerServer=(()=>{
                                if(selectedPlayerHost!=='nhplayer.com' ||
                                    !/^\/v\/[A-Za-z0-9]{8,40}\/?$/.test(location.pathname) ||
                                    location.search || location.hash)return '';
                                const found=new Set();
                                for(const item of [...document.querySelectorAll(
                                    '.servers li[data-id],ul.servers li[data-id],'+
                                    '#servers li[data-id]')].slice(0,12)){
                                    try{
                                        const parsed=new URL(item.getAttribute('data-id')||'',
                                            location.href);
                                        const keys=[...parsed.searchParams.keys()].sort();
                                        if(parsed.protocol!=='https:' ||
                                            parsed.hostname!=='nhplayer.com' ||
                                            parsed.port || parsed.username || parsed.password ||
                                            parsed.pathname!=='/player.php' || parsed.hash ||
                                            keys.join(',')!=='i,s,vid' ||
                                            ['i','s','vid'].some(k=>
                                                !parsed.searchParams.get(k)))continue;
                                        found.add(parsed.href);
                                    }catch(_){}
                                }
                                return found.size===1?
                                    '<ul class="servers"><li data-id="'+
                                    esc([...found][0])+'"></li></ul>':'';
                            })();
                            const nhAjaxMedia=(()=>{
                                if(selectedPlayerHost!=='nhplayer.com' ||
                                    location.pathname!=='/player.php')return '';
                                const keys=[...new URLSearchParams(
                                    location.search).keys()].sort();
                                if(keys.join(',')!=='i,s,vid')return '';
                                const value=typeof window.videoReady==='string'
                                    ?window.videoReady:'';
                                try{
                                    const selected=new URL(value);
                                    if(selected.protocol!=='https:' ||
                                        selected.hostname!=='r2.1hanime.com' ||
                                        !selected.pathname.toLowerCase().endsWith('.mp4') ||
                                        [...selected.searchParams.keys()].sort()
                                            .join(',')!=='verify' ||
                                        /preview|teaser|sample|trailer/i
                                            .test(selected.pathname))
                                        return '';
                                    return selected.href;
                                }catch(_){return '';}
                            })();
                            const nhAjaxVideo=nhAjaxMedia?
                                '<video src="'+esc(nhAjaxMedia)+
                                '" data-duration="0"></video>':'';
                            // JAVIDEO's visible Play control activates the
                            // selected StreamTape iframe. The initial page's
                            // only HTML5 video is an unrelated muted ad; never
                            // serialize that ad as the requested original.
                            if(selectedPlayerHost==='javideo.net'){
                                const play=document.querySelector('#clickfakeplayer');
                                const selected=document.querySelector(
                                    '.embed-responsive.player:not(.d-none) iframe[src]');
                                if(play && !selected){
                                    // The publisher Play widget sometimes starts
                                    // before its own JS listener attaches. The
                                    // original one-shot click left no selected
                                    // iframe in 27s on a genuine live test.
                                    const attempts=Number(play.dataset.asterionAttempts||0);
                                    const last=Number(play.dataset.asterionLastClick||0);
                                    if(attempts<3 && Date.now()-last>=3500){
                                        play.dataset.asterionAttempts=String(attempts+1);
                                        play.dataset.asterionLastClick=String(Date.now());
                                        play.click();
                                    }
                                }
                            }
                            // Activate only the selected in-article player.
                            // 3DHentaiX and VOE may show an inert poster until
                            // the user-selected player's own Play control runs.
                            // Preserve the source identity/duration guards in
                            // SiteSpecificOriginalSources; never inspect ads.
                            const exactJavideo=
                                selectedPlayerHost==='play.javideo.net' &&
                                /^\/(?:embed|player)\/[A-Za-z0-9_-]{5,100}\/?$/
                                    .test(location.pathname);
                            const exactNHPlayer=
                                selectedPlayerHost==='nhplayer.com' &&
                                /^\/v\/[A-Za-z0-9]{8,40}\/?$/
                                    .test(location.pathname);
                            if(selectedPlayerHost==='3dhentaix.com' ||
                                exactJavideo || exactNHPlayer ||
                                (['voe.sx','kamehaus.net',
                                    'streamtape.com','streamtape.to',
                                    'streamtape.net','streamtape.site','sbtape.com']
                                    .includes(selectedPlayerHost) &&
                                 /^\/e\/[A-Za-z0-9_-]{6,80}\/?$/.test(location.pathname))){
                                const roots=selectedPlayerHost==='3dhentaix.com'?
                                    [...document.querySelectorAll(
                                        'main .video-player,article .video-player,'+
                                        'main #player,article #player')]:
                                    (()=>{
                                        const wrappers=[...document.querySelectorAll(
                                            '#player,.video-player,.vjs-player,.player-container')];
                                        return wrappers.length?wrappers:
                                            [...document.querySelectorAll(
                                                'video#mainvideo,video#player,video.video-js')];
                                    })();
                                const selected=roots.filter(el=>
                                    !el.closest('aside,.related,.recommendations,'+
                                        '.ads,.advertisement,.sidebar'));
                                if(selected.length===1){
                                    const root=selected[0];
                                    const play=root.querySelector(
                                        'button[aria-label="Play"],'+
                                        'button[title="Play"],.vjs-big-play-button,'+
                                        '.plyr__control[data-plyr="play"],'+
                                        '.fp-play,button.play-button') ||
                                         (root.tagName.toLowerCase()==='video'?
                                             root.parentElement?.querySelector(
                                                 '.vjs-big-play-button,button[aria-label="Play"]'):
                                             null);
                                    if(play && !play.dataset.asterionActivated){
                                        play.dataset.asterionActivated='true';
                                        play.click();
                                    }
                                    const v=root.tagName.toLowerCase()==='video'?
                                        root:root.querySelector('video');
                                    if(v && v.paused){
                                        try{
                                            const p=v.play();
                                            if(p&&p.catch)p.catch(()=>{});
                                        }catch(_){}
                                    }
                                }
                            }
                            if(selectedPlayerHost==='hentaibros.net'){
                                const play=document.querySelector(
                                    '.video-player .flowplayer.is-splash .fp-play');
                                if(play && !play.dataset.asterionActivated){
                                    play.dataset.asterionActivated='true';
                                    play.click();
                                }
                            }
                            if((selectedPlayerHost==='fullporner.com' &&
                                /^\/player\/\d{1,12}\/\d{1,4}\/?$/.test(
                                    location.pathname)) ||
                               (selectedPlayerHost==='mydaddy.cc' &&
                                /^\/video\/[A-Za-z0-9_-]{8,160}\/?$/.test(
                                    location.pathname))){
                                const v=selectedPlayerHost==='fullporner.com'?
                                    document.querySelector('video#flvv'):
                                    document.querySelector('video');
                                if(v){
                                    try{v.load();}catch(_){}
                                    if(v.paused)try{
                                        const p=v.play();
                                        if(p&&p.catch)p.catch(()=>{});
                                    }catch(_){}
                                }
                            }
                            const players=[...document.querySelectorAll(
                                'main video,article video,.video-player video,video#flvv,'+
                                '#player video,.video-js video,video source[src],'+
                                (['mydaddy.cc','nhplayer.com','voe.sx','kamehaus.net',
                                    'hentaihaven.xxx','play.javideo.net','streamtape.com','streamtape.to',
                                    'streamtape.net','streamtape.site','sbtape.com',
                                    'video.playerrecord.cfd','vidara.to']
                                    .includes(selectedPlayerHost) ||
                                    (selectedPlayerHost==='cosplaytele.com' &&
                                     /^\/(?:embed|player)\/[A-Za-z0-9_-]{5,100}\/?$/
                                        .test(location.pathname))
                                        ?'video[src]':'video#flvv'))]
                                .filter(el=>selectedPlayerHost!=='javideo.net')
                                .map(el=>el.tagName.toLowerCase()==='video'?
                                    el:el.closest('video')).filter((el,i,list)=>
                                    !!el && list.indexOf(el)===i &&
                                    !el.closest('.related,.recommendations,'+
                                        '.happy-under-player,.ads,.advertisement') &&
                                    (selectedPlayerHost!=='hentaibros.net' ||
                                        !!el.closest('.video-player .flowplayer')))
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
                            const publisher=location.hostname.replace(/^www\./,'');
                            // Only a first-party selected page's own poster
                            // metadata, not a card from a related-video list.
                            // The compact browser snapshot previously stripped
                            // OG images, so poster repair still saw zero images
                            // even when the authorized Android browser worked.
                            const selectedPoster=(()=>{
                                const meta=document.querySelector(
                                    'meta[property="og:image:secure_url"],'+
                                    'meta[property="og:image"],'+
                                    'meta[name="twitter:image"],'+
                                    'meta[property="twitter:image"]');
                                const actualVideo=document.querySelector(
                                    'main video[poster],article video[poster],'+
                                    '.video-player video[poster]');
                                const raw=meta?.getAttribute('content')||
                                    actualVideo?.getAttribute('poster')||'';
                                if(!raw)return '';
                                try{
                                    const image=new URL(raw,location.href);
                                    if(image.protocol!=='https:' ||
                                        image.username || image.password ||
                                        image.port || image.hash ||
                                        /\.(?:svg|html?|js|css)$/i
                                            .test(image.pathname))return '';
                                    return '<meta property="og:image" content="'+
                                        esc(image.href)+'">';
                                }catch(_){return '';}
                            })();
                            // The exact 4KHD official archive bridge injects
                            // #custom_button only after its public JS loads.
                            // Serialize its real link or report a pending
                            // state for bounded browser inspection. Never
                            // click adverts or fabricate a TeraBox URL.
                            if(publisher==='m.4khd.com' &&
                                /^\/(?:link\/)?[A-Za-z0-9_-]{4,90}\/?$/.test(location.pathname)){
                                const button=document.querySelector(
                                    'a#custom_button[href]');
                                // A button with href="#" exists before the
                                // publisher initializes its genuine TeraBox
                                // share. It must NOT satisfy readiness.
                                let ready=false;
                                try{
                                    const destination=new URL(
                                        button?.getAttribute('href')||'',location.href);
                                    const h=destination.hostname.toLowerCase();
                                    const allowed=['terabox.com','terabox.app',
                                        'teraboxapp.com','1024tera.com',
                                        '1024terabox.com','4funbox.com',
                                        'nephobox.com','mirrobox.com',
                                        'momerybox.com'];
                                    ready=destination.protocol==='https:' &&
                                        allowed.some(host=>h===host ||
                                            h.endsWith('.'+host)) &&
                                        !destination.username &&
                                        !destination.password &&
                                        !destination.port && !destination.hash &&
                                        (destination.pathname.length>3 ||
                                            destination.searchParams.has('surl'));
                                }catch(_){}
                                if(!ready)return '<html><head>'+
                                    '<meta name="asterion-4khd-link-pending" content="true">'+
                                    '</head></html>';
                            }
                            // Real HentaiBros public HTML may contain no
                            // wpfp_* config until JavaScript initializes the
                            // selected Flowplayer. Preserve that publisher
                            // data-item in this compact WebView snapshot so
                            // the native selected-original resolver can bind
                            // the exact work rather than choose an unrelated
                            // HTML5 preview with a valid MP4 MIME.
                            const hentaibrosPlayerConfig=publisher==='hentaibros.net'
                                ? (()=>{
                                    const nodes=[...document.querySelectorAll(
                                        '.video-player [data-item]')].filter(el=>
                                        (/^wpfp_\d{1,10}$/.test(el.id||'') ||
                                          el.classList.contains('flowplayer')) &&
                                        !el.closest('aside,.related,.recommended,'+
                                            '.recommendations,.ads,.advertisement,.sidebar'));
                                    if(nodes.length!==1)return '';
                                    const data=nodes[0].getAttribute('data-item')||'';
                                    return data?
                                        '<div id="'+esc(nodes[0].id||'')+
                                        '" class="flowplayer" data-item="'+esc(data)+
                                        '"></div>':'';
                                })():'';
                            const currentPath=location.pathname||'';
                            // Pornhub's real selected watch publishes one
                            // flashvars_<id> object, not an HTML5 video[src].
                            // Keep only the first-party selected player
                            // configuration; never serialize ads, suggested
                            // video cards, tokens from unrelated sites, or
                            // an unverified external player.
                            if(['pornhub.com','m.pornhub.com',
                                'fr.pornhub.com','en.pornhub.com'].includes(publisher) &&
                                currentPath==='/view_video.php' &&
                                /^[A-Za-z0-9_-]{5,50}$/.test(
                                    new URLSearchParams(location.search).get('viewkey')||'')){
                                const names=Object.keys(window).filter(k=>
                                    /^flashvars_[A-Za-z0-9_]{1,72}$/.test(k) &&
                                    window[k] && Array.isArray(
                                        window[k].mediaDefinitions));
                                if(names.length===1){
                                    const data=JSON.stringify(window[names[0]]);
                                    if(data && data.length<350000)
                                        return '<html><head>'+selectedPoster+
                                            '</head><script>var '+names[0]+
                                            ' = '+data.replace(/</g,'\\u003c')+
                                            ';</script></html>';
                                }
                            }
                            const embeds=[...document.querySelectorAll(
                                'iframe[src]')].filter(el=>{
                                let u;
                                try{u=new URL(el.getAttribute('src'),location.href);}
                                catch(_){return false;}
                                if(u.protocol!=='https:')return false;
                                const host=u.hostname.replace(/^www\./,'');
                                if(publisher==='archivebate.com')
                                    return /^\/(?:u\/)?watch\/\d{1,16}\/?$/.test(currentPath) &&
                                        host==='mixdrop.ag' &&
                                        /^\/e\/[A-Za-z0-9_-]{5,64}\/?$/.test(u.pathname);
                                if(publisher==='livecamrips.to')
                                    return /^\/video\/\d{5,12}\/?$/.test(currentPath) &&
                                        el.id==='iframe-video' &&
                                        !el.closest('aside,.related,.recommended,'+
                                            '.recommendations,.ads,.advertisement,.sidebar') &&
                                        ((host==='vidara.to' &&
                                            /^\/[A-Za-z0-9_-]{8,50}\/?$/.test(u.pathname) &&
                                            !u.search && !u.hash) ||
                                        (['mdzsmutpcvykb.net','mixdrop.ag',
                                            'mixdrop.co','mixdrop.to'].includes(host) &&
                                            /^\/e\/[A-Za-z0-9_-]{5,64}\/?$/.test(u.pathname)));
                                if(publisher==='pornavhd.com')
                                    return /^\/20\d{2}\/(?:0?[1-9]|1[0-2])\/(?:0?[1-9]|[12]\d|3[01])\/[a-z0-9][a-z0-9_-]{2,220}\/?$/i
                                        .test(currentPath) &&
                                        !!el.closest('main,article') &&
                                        !el.closest('aside,.related,.recommended,'+
                                            '.recommendations,.ads,.advertisement,.sidebar,[data-ad]') &&
                                        host==='video.playerrecord.cfd' &&
                                        /^\/e\/[A-Za-z0-9_-]{8,100}\/?$/.test(u.pathname) &&
                                        !u.search && !u.hash;
                                if(publisher==='javideo.net'){
                                    const wrapper=el.closest('.embed-responsive.player');
                                    if(!wrapper || wrapper.classList.contains('d-none'))
                                        return false;
                                    const firstParty=host==='play.javideo.net' &&
                                        /^\/embed\/[A-Za-z0-9_-]{5,100}\/?$/.test(u.pathname) &&
                                        u.search.length===0;
                                    return firstParty ||
                                        (['streamtape.com','streamtape.to',
                                        'streamtape.net','streamtape.site',
                                        'sbtape.com'].includes(host) &&
                                        /^\/(?:e|v|embed)\/[A-Za-z0-9_-]{5,100}\/?$/
                                            .test(u.pathname));
                                }
                                if(publisher==='cosplaytele.com'){
                                    // Only the selected article's first-party
                                    // player frame; no advertising iframe,
                                    // related post, or unrelated gallery.
                                    return /^\/[a-z0-9][a-z0-9_-]{2,180}\/?$/i
                                        .test(currentPath) &&
                                        !!el.closest('main,article,.entry-content,.post-content') &&
                                        !el.closest('aside,.related,.recommended,'+
                                            '.recommendations,.ads,.advertisement,.sidebar,[data-ad]') &&
                                        host==='cosplaytele.com' &&
                                        /^\/(?:embed|player|video)\/[A-Za-z0-9_-]{5,100}\/?$/
                                            .test(u.pathname) && !u.search && !u.hash;
                                }
                                if(publisher==='helloporn.com')
                                    return /^\/video-\d{1,10}-[a-z0-9-]+\/?$/i
                                        .test(currentPath) &&
                                        !!el.closest('#player,.video-player,.player,main,article') &&
                                        !el.closest('aside,.related,.recommendations,.ads,.sidebar') &&
                                        host==='voe.sx' &&
                                        /^\/e\/[A-Za-z0-9_-]{6,80}\/?$/.test(u.pathname);
                                if(publisher==='uncensoredhentai.xxx')
                                    return /^\/(?:watch|video)\//.test(currentPath) &&
                                        host==='nhplayer.com' &&
                                        /^\/v\/[A-Za-z0-9]{8,40}\/?$/.test(u.pathname);
                                if(publisher==='fullporner.com')
                                    return /^\/watch\/[a-f0-9]{24}\/?$/i.test(currentPath) &&
                                        host==='fullporner.com' &&
                                        /^\/player\/\d{1,12}\/\d{1,4}\/?$/.test(u.pathname);
                                if(['jav.guru','javideo.net','mp4hentai.com',
                                    'onlyhentaistuff.com','hentaihaven.xxx']
                                    .includes(publisher))
                                    return host===publisher &&
                                        /^\/(?:embed|player|watch|video)\//.test(u.pathname);
                                return false;
                            }).slice(0,5).map(el=>
                                '<iframe '+
                                (publisher==='livecamrips.to' &&
                                    el.id==='iframe-video'
                                    ?'id="iframe-video" ':'')+
                                'src="'+esc(new URL(
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
                            // HentaiHaven's exact episode exposes its HLS
                            // through a first-party VideoObject JSON-LD. The
                            // compact WebView snapshot previously discarded
                            // ALL JSON-LD, so the episode resolver could find
                            // no original even if browser playback succeeded.
                            if(publisher==='hentaihaven.xxx' &&
                                /^\/watch\/[a-z0-9-]{4,220}\/episode-[0-9]{1,5}\/?$/i
                                    .test(currentPath)){
                                const published=new Set();
                                const selected=location.href.replace(/\/$/,'');
                                const valid=u=>{
                                    try{
                                        const p=new URL(u);
                                        return p.protocol==='https:' &&
                                            p.hostname==='octopusmanifest.org' &&
                                            p.port==='' && !p.search && !p.hash &&
                                            /^\/[A-Za-z0-9_.\/-]{8,500}\.m3u8$/i
                                                .test(p.pathname) &&
                                            (p.pathname.match(/\//g)||[]).length===2 &&
                                            !/preview|sample|trailer|teaser/i
                                                .test(p.pathname);
                                    }catch(_){return false;}
                                };
                                for(const node of [...document.querySelectorAll(
                                    'script[type="application/ld+json"]')].slice(0,20)){
                                    try{
                                        const data=JSON.parse(node.textContent||'');
                                        const walk=(obj,depth)=>{
                                            if(depth>8 || !obj || typeof obj!=='object')
                                                return;
                                            if(Array.isArray(obj)){
                                                obj.slice(0,100).forEach(x=>walk(x,depth+1));
                                                return;
                                            }
                                            const types=Array.isArray(obj['@type'])
                                                ?obj['@type']:[obj['@type']];
                                            if(types.includes('VideoObject')){
                                                const declared=String(obj.url||'')
                                                    .replace(/\/$/,'');
                                                if((!declared || declared===selected) &&
                                                    valid(obj.contentUrl))
                                                    published.add(obj.contentUrl);
                                            }
                                            Object.values(obj).forEach(v=>
                                                typeof v==='object'&&walk(v,depth+1));
                                        };
                                        walk(data,0);
                                    }catch(_){}
                                }
                                if(published.size===1){
                                    const link=[...published][0];
                                    const evidence=JSON.stringify({
                                        '@type':'VideoObject',
                                        url:location.href,
                                        contentUrl:link
                                    });
                                    return '<html><head><script type="application/ld+json">'+
                                        evidence+'</script></head><body></body></html>';
                                }
                            }
                            // xChina's actual selected HLS belongs to the
                            // first-party #video-player[data-src], not an
                            // HTML5 source. The compact DOM used to drop that
                            // element and make every browser fallback fail.
                            if(['xchina.co','en.xchina.co'].includes(publisher)){
                                const match=currentPath.match(
                                    /^\/video\/(?:id-)?([a-f0-9]{10,32})(?:\.html)?\/?$/i);
                                const nodes=[...document.querySelectorAll(
                                    '#video-player[data-src]')].filter(el=>
                                    !el.closest('aside,.related,.recommended,'+
                                        '.recommendations,.ads,.advertisement,.sidebar,[data-ad]'));
                                if(match && nodes.length===1){
                                    const source=nodes[0].getAttribute('data-src')||'';
                                    try{
                                        const link=new URL(source,location.href);
                                        const valid=link.protocol==='https:' &&
                                            ['xchina.co','www.xchina.co','en.xchina.co']
                                                .includes(link.hostname.toLowerCase()) &&
                                            !link.username && !link.password &&
                                            !link.port && !link.search && !link.hash &&
                                            link.pathname.toLowerCase()===
                                                '/hls/'+match[1].toLowerCase()+'/master.m3u8';
                                        if(valid)
                                            return '<html><head>'+selectedPoster+'<meta name="'+
                                                'asterion-selected-xchina-hls" content="true">'+
                                                '</head><body><main><div id="video-player"'+
                                                ' data-src="'+esc(link.href)+'"></div>'+
                                                '</main></body></html>';
                                    }catch(_){}
                                }
                            }
                            // The original PimpBunny KVS flashvars contains a
                            // publisher-signed /get_file/ video_url and ID.
                            // Browser snapshots dropped the selected kt_player
                            // and inline JS, making the existing strict parser
                            // believe only short HTML5 preview files existed.
                            if(publisher==='pimpbunny.com' &&
                                /^\/videos\/[a-z0-9][a-z0-9-]{2,200}\/?$/i
                                    .test(currentPath)){
                                const players=[...document.querySelectorAll(
                                    '#kt_player')].filter(el=>
                                    !el.closest('aside,.related,.recommended,'+
                                        '.recommendations,.ads,.advertisement,.sidebar,[data-ad]'));
                                const scripts=[...document.querySelectorAll(
                                    'script:not([src])')].map(el=>el.textContent||'')
                                    .filter(js=>js.length>30 && js.length<120000 &&
                                        js.includes('kt_player') &&
                                        /\bvideo_url\b/.test(js) &&
                                        /\bvideo_id\b/.test(js));
                                if(players.length===1 && scripts.length===1 &&
                                    !/<\/script/i.test(scripts[0]))
                                    return '<html><head>'+selectedPoster+
                                        '</head><body><main>'+
                                        '<div id="kt_player"></div><script>'+
                                        scripts[0]+'</script></main></body></html>';
                            }
                            // Jav Guru's real shortcode player is not a normal
                            // iframe. The old synthetic DOM omitted its article
                            // ID, data-localize keys and inline iframe_url JSON,
                            // making the exact player resolver ALWAYS return
                            // zero candidates after WebView/browser fallback.
                            if(publisher==='jav.guru' &&
                                new RegExp('^/[0-9]{4,10}/[a-z0-9_-]{4,240}/?$','i').test(currentPath)){
                                const postId=currentPath.split('/').filter(Boolean)[0];
                                const article=document.getElementById('post-'+postId);
                                const extra=document.getElementById('wp-btn-iframe-js-extra');
                                if(article && extra){
                                    const selected=[...article.querySelectorAll(
                                        'a.wp-btn-iframe__shortcode[data-localize]')]
                                        .slice(0,18).map(btn=>
                                            '<a class="wp-btn-iframe__shortcode" data-localize="'+
                                            esc(btn.getAttribute('data-localize')||'')+'">'+
                                            esc((btn.innerText||btn.textContent||'').trim())+'</a>'
                                        ).join('');
                                    const config=(extra.textContent||'').slice(0,100_000);
                                    if(selected && config.includes('iframe_url'))
                                        return '<html><head>'+selectedPoster+
                                            '</head><body><article id="post-'+postId+'">'+
                                            selected+'</article><script>'+config+
                                            '</script></body></html>';
                                }
                            }
                            const realH1=document.querySelector('h1')?.innerText||'';
                            return '<html><head><title>'+esc(title)+
                                '</title>'+selectedPoster+(mirroredPending?
                                    '<meta name="asterion-host-list-pending" content="true">':
                                    '')+'</head><body><h1>'+esc(realH1)+
                                '</h1><h4>'+esc(chapterTitle)+
                                '</h4>'+anchors+'<main><div id="article">'+
                                paragraphs+'</div><div class="video-player">'+
                                hentaibrosPlayerConfig+players+
                                nhPlayerServer+nhAjaxVideo+embeds+'</div><div id="reader">'+images+
                                '</div></main></body></html>';
                        })()"""){raw->
                            val page=runCatching{JSONTokener(raw).nextValue() as? String}
                                .getOrNull()
                            if(page.isNullOrBlank()){
                                error.set("The browser still shows a website verification page.")
                                latch.countDown()
                            }else if(host=="archivebate.com" &&
                                (SiteSession.bound(context,url) || publicEligible(url)) &&
                                Uri.parse(url).path.orEmpty().trim('/')=="" &&
                                !ArchivebateListingPresence.hasSelectedWatch(page) &&
                                archivebateWaits++<6){
                                // The public Continue step sometimes populates
                                // its real /watch/ listing asynchronously.
                                // Wait for observed cards; do NOT invent entries
                                // or click the site's verification controls.
                                main.postDelayed({
                                    if(latch.count>0L)onPageFinished(v,finished)
                                },1_500L)
                            }else if(host=="m.4khd.com" &&
                                page.contains("asterion-4khd-link-pending") &&
                                fourKhdBridgeWaits++<7){
                                main.postDelayed({
                                    if(latch.count>0L)onPageFinished(v,finished)
                                },1_000L)
                            }else if(host=="mirrored.to" &&
                                page.contains("asterion-host-list-pending") &&
                                mirroredHostWaits++<8){
                                // Mirrored's observed dl=0 page initially
                                // shows "Fetching hosting links" before the
                                // user's selected file-host list is injected.
                                // An empty shell is not a video or a success.
                                main.postDelayed({
                                    if(latch.count>0L)onPageFinished(v,finished)
                                },1_200L)
                            }else if(selectedPlayerPage &&
                                selectedPlayerWaits++<
                                    SelectedPlayerWaitPolicy.maxPolls(host) &&
                                ((if(host=="nhplayer.com" &&
                                    Regex("""^/v/[A-Za-z0-9]{8,40}/?$""")
                                        .matches(Uri.parse(url).path.orEmpty())) {
                                    // A /v/ chooser will never have a media
                                    // <video>; stop waiting as soon as the one
                                    // verified child server is serialized.
                                    com.asterion.videoflow.SiteSpecificOriginalSources
                                        .selectedNhPlayerServer(url,page)==null
                                } else if(host=="nhplayer.com" &&
                                    Uri.parse(url).path=="/player.php") {
                                    // Empty or ad video tags may arrive before
                                    // the selected first-party AJAX response.
                                    // Wait only for the genuine /v/ linked
                                    // r2.1hanime.com .mp4?verify=... source.
                                    !SelectedPlayerWaitPolicy.selectedNhPlayerReady(
                                        playerReferer.orEmpty(),url,page)
                                } else if(host=="javideo.net") {
                                    // Only the selected iframe appears after
                                    // JAVIDEO's Play button; its HTML5 video is
                                    // a separate advert, not the original.
                                    com.asterion.videoflow.SiteSpecificOriginalSources
                                        .javideoSelected(
                                            org.jsoup.Jsoup.parse(page,url),url)
                                        .isEmpty()
                                } else if(host in setOf("xchina.co","en.xchina.co")) {
                                    !page.contains("asterion-selected-xchina-hls") &&
                                    !Regex("""<video\s+src="https://[^"]+""")
                                        .containsMatchIn(page)
                                } else if(host=="pimpbunny.com") {
                                    !page.contains("id=\"kt_player\"") &&
                                    !Regex("""<video\s+src="https://[^"]+""")
                                        .containsMatchIn(page)
                                } else if(host=="pornavhd.com") {
                                    !page.contains("video.playerrecord.cfd/e/") &&
                                    !Regex("""<video\s+src="https://[^"]+""")
                                        .containsMatchIn(page)
                                } else if(host=="rule34video.com") {
                                    com.asterion.videoflow.Rule34VideoOriginalSource
                                        .parse(page,url).isEmpty()
                                } else if(host=="pornhub.com") {
                                    !page.contains("flashvars_") ||
                                        !page.contains("mediaDefinitions")
                                } else !Regex("""<video\s+src="https://[^"]+""")
                                    .containsMatchIn(page)) ||
                                    (host in setOf("hentaibros.net","3dhentaix.com") &&
                                     Regex("""data-duration="([0-9]{1,9})""")
                                         .findAll(page).none{
                                             (it.groupValues[1].toLongOrNull()?:0L)>=90L
                                         }))){
                                // HentaiBros and 3DHentai often expose an
                                // initially empty or 0-second JS player.
                                // Wait for the actual original's duration,
                                // never save a short card preview as a full file.
                                main.postDelayed({
                                    if(latch.count>0L)onPageFinished(v,finished)
                                },1_400L)
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
            if(playerReferer==null)web.loadUrl(url)
            else web.loadUrl(url,mapOf("Referer" to playerReferer))
            }catch(t:Throwable){
                error.set("WebView initialization failed: "+t.javaClass.simpleName)
                latch.countDown()
            }
        }
        val completed=try {
            latch.await(45,TimeUnit.SECONDS)
        }finally{
            latch.countDown()
            main.post{runCatching{
                instance.getAndSet(null)?.apply{stopLoading();destroy()}
            }}
        }
        if(!completed)throw IllegalStateException("Browser page retrieval timed out: "+url)
        return result.get()?:throw IllegalStateException(
            error.get()?:"Browser could not load this chapter.")
    }
}
