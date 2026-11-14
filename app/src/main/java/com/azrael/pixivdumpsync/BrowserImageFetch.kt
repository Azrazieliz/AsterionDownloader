package com.azrael.pixivdumpsync

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Authenticated browser-media fallback. Navigate to the actual image URL,
 * then use a same-origin browser fetch to write the original media bytes
 * in 24-KB chunks. No re-encoding or Canvas quality loss.
 */
object BrowserImageFetch {
    private const val MAX_BYTES=60*1024*1024
    fun download(context:Context,url:String,referer:String,target:File) {
        require(com.azrael.galleryflow.SiteSession.bound(context,referer)) {
            "Connect this comic website in its browser before retrying media."
        }
        check(Looper.myLooper()!=Looper.getMainLooper()) {
            "Browser media requests must not run on the UI thread."
        }
        val main=Handler(Looper.getMainLooper())
        val latch=CountDownLatch(1)
        val error=AtomicReference<String?>()
        val instance=AtomicReference<WebView?>()
        val output=FileOutputStream(target)
        val lock=Any()
        var size=0L
        var finished=false
        class ChunkReceiver {
            @JavascriptInterface fun push(base64:String) {
                synchronized(lock) {
                    if(finished)return
                    try {
                        val bytes=Base64.decode(base64,Base64.DEFAULT)
                        require(size+bytes.size<=MAX_BYTES){"Image exceeds 60 MB limit"}
                        output.write(bytes)
                        size+=bytes.size
                    }catch(t:Exception) {
                        error.set(t.message)
                        finished=true
                        runCatching{output.close()}
                        latch.countDown()
                    }
                }
            }
            @JavascriptInterface fun success(expected:Int){
                synchronized(lock) {
                    if(finished)return
                    finished=true
                    runCatching{output.close()}
                    if(size<128L||expected.toLong()!=size)
                        error.set("Browser image incomplete: "+size+" / "+expected)
                    latch.countDown()
                }
            }
            @JavascriptInterface fun failure(message:String){
                synchronized(lock) {
                    if(finished)return
                    finished=true
                    error.set(message.take(320))
                    runCatching{output.close()}
                    latch.countDown()
                }
            }
        }
        main.post {
            val web=WebView(context)
            instance.set(web)
            web.settings.javaScriptEnabled=true
            web.settings.domStorageEnabled=true
            web.settings.allowFileAccess=false
            web.settings.allowContentAccess=false
            CookieManager.getInstance().setAcceptCookie(true)
            web.addJavascriptInterface(ChunkReceiver(),"AsterionMediaChunk")
            web.webViewClient=object:WebViewClient(){
                override fun onPageFinished(v:WebView,finishedUrl:String){
                    if(latch.count==0L)return
                    v.evaluateJavascript("""(async function(){
                        try {
                            const response=await fetch(window.location.href,{
                                credentials:'include',
                                cache:'no-store'
                            });
                            if(!response.ok)throw new Error('Browser image HTTP '+response.status);
                            const contentType=response.headers.get('content-type')||'';
                            if(!/^image\//i.test(contentType)&&
                                !/octet-stream/i.test(contentType))
                                throw new Error('Not an image: '+contentType);
                            const bytes=new Uint8Array(await response.arrayBuffer());
                            if(bytes.length<128||bytes.length>62914560)
                                throw new Error('Image size outside permitted range');
                            for(let i=0;i<bytes.length;i+=24576){
                                const part=bytes.subarray(i,i+24576);
                                let binary='';
                                for(let j=0;j<part.length;j++)
                                    binary+=String.fromCharCode(part[j]);
                                AsterionMediaChunk.push(btoa(binary));
                            }
                            AsterionMediaChunk.success(bytes.length);
                        }catch(err){
                            AsterionMediaChunk.failure(String(err.message||err));
                        }
                    })()""",null)
                }
            }
            web.loadUrl(url,mapOf("Referer" to referer))
        }
        val completed=latch.await(80,TimeUnit.SECONDS)
        main.post {
            instance.getAndSet(null)?.apply {
                removeJavascriptInterface("AsterionMediaChunk")
                stopLoading()
                destroy()
            }
        }
        synchronized(lock){if(!finished)runCatching{output.close()}}
        if(!completed || error.get()!=null) {
            target.delete()
            throw IllegalStateException(error.get()?:"Browser image fetch timed out")
        }
    }
}
