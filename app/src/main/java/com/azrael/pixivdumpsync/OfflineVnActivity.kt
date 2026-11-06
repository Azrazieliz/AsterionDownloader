package com.azrael.pixivdumpsync

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/** Actually runs local HTML5 visual novels from user-owned ZIPs on Android.
 * Does not claim that Windows executable or Ren'Py ZIP is automatically portable. */
class OfflineVnActivity:Activity(){
    private lateinit var status:TextView
    private var browser:WebView?=null
    private val urlRoot="https://vn.asterion.invalid/"
    private fun say(s:String)=runOnUiThread{if(::status.isInitialized)status.text=s}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val source=intent.data?:run {finish();return}
        val ui=NocturneDesign
        val layout=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setBackgroundColor(ui.background)
        }
        status=ui.text(this,"Preparing HTML5 game archive…",15f,ui.white,true)
        layout.addView(status,ui.lp(this,12))
        setContentView(layout)
        thread(name="asterion-html5-vn-import"){
            try {
                val extraction=unpack(source)
                say("Starting HTML5 visual novel")
                runOnUiThread{launchGame(layout,extraction.first,extraction.second)}
            }catch(t:Exception){
                say("Not an Android-playable HTML5 VN ZIP: "+t.message+
                    "\nRen'Py, Kirikiri and NScripter require their own Android runtimes.")
            }
        }
    }
    private fun unpack(uri:Uri):Pair<File,File>{
        val digest=MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray())
            .take(12).joinToString(""){"%02x".format(it)}
        val base=File(filesDir,"html5-vns/$digest")
        val ready=File(base,".asterion-ready")
        if(ready.isFile){
            val entry=File(base,ready.readText().trim())
            if(entry.isFile)return base to entry
        }
        base.mkdirs()
        val root=base.canonicalPath+File.separator
        var n=0
        var total=0L
        val maxBytes=2L*1024*1024*1024
        val maxEntries=20000
        contentResolver.openInputStream(uri)?.buffered().use{stream->
            require(stream!=null){"Unable to read selected archive"}
            ZipInputStream(stream).use{zip->
                while(true){
                    val e=zip.nextEntry?:break
                    n++
                    require(n<=maxEntries){"ZIP contains more than $maxEntries files"}
                    val relative=e.name.replace('\\','/').trimStart('/')
                    if(relative.isBlank()){zip.closeEntry();continue}
                    val target=File(base,relative)
                    require(target.canonicalPath.startsWith(root)){"Archive contains unsafe paths"}
                    if(e.isDirectory){target.mkdirs();zip.closeEntry();continue}
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use{output->
                        val bytes=ByteArray(65536)
                        while(true){
                            val got=zip.read(bytes)
                            if(got<0)break
                            total+=got
                            require(total<=maxBytes){"HTML5 archive exceeds 2 GB extracted"}
                            output.write(bytes,0,got)
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        val index=base.walkTopDown().maxDepth(6)
            .firstOrNull{it.isFile && it.name.equals("index.html",true)}
            ?:throw IllegalArgumentException("No index.html found in the archive")
        ready.writeText(index.relativeTo(base).invariantSeparatorsPath)
        return base to index
    }
    private fun launchGame(layout:LinearLayout,base:File,index:File){
        val ui=NocturneDesign
        status.text=index.parentFile?.name.orEmpty().ifBlank{"HTML5 visual novel"}
        val root=base.canonicalPath+File.separator
        val page=WebView(this).apply{
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=true
            settings.allowFileAccess=false
            settings.allowContentAccess=false
            settings.setSupportZoom(true)
            setBackgroundColor(Color.BLACK)
            webViewClient=object:WebViewClient(){
                override fun shouldOverrideUrlLoading(
                    view:WebView,request:WebResourceRequest
                ):Boolean = request.url.host!="vn.asterion.invalid"
                override fun shouldInterceptRequest(
                    view:WebView,request:WebResourceRequest
                ):WebResourceResponse?{
                    val address=request.url
                    if(address.host!="vn.asterion.invalid")
                        return WebResourceResponse("text/plain","UTF-8",403,
                            "Blocked",mapOf("Cache-Control" to "no-store"),
                            "Blocked external game request".byteInputStream())
                    val relative=Uri.decode(address.encodedPath.orEmpty()).trimStart('/')
                    val file=File(base,relative)
                    if(!file.canonicalPath.startsWith(root) || !file.isFile)
                        return WebResourceResponse("text/plain","UTF-8",404,
                            "Not Found",emptyMap(),"Missing game asset".byteInputStream())
                    val ext=file.extension.lowercase()
                    val type=when(ext){
                        "html","htm"->"text/html"
                        "js","mjs"->"application/javascript"
                        "css"->"text/css"
                        "png"->"image/png"
                        "jpg","jpeg"->"image/jpeg"
                        "webp"->"image/webp"
                        "svg"->"image/svg+xml"
                        "json"->"application/json"
                        "wasm"->"application/wasm"
                        "mp3"->"audio/mpeg"
                        "ogg"->"audio/ogg"
                        "wav"->"audio/wav"
                        "mp4"->"video/mp4"
                        "woff"->"font/woff"
                        "woff2"->"font/woff2"
                        "ttf"->"font/ttf"
                        else->"application/octet-stream"
                    }
                    return WebResourceResponse(type,
                        if(type.startsWith("text/")||type=="application/javascript")"UTF-8" else null,
                        FileInputStream(file))
                }
            }
        }
        browser=page
        layout.addView(page,LinearLayout.LayoutParams(-1,0,1f))
        page.loadUrl(urlRoot+index.relativeTo(base).invariantSeparatorsPath)
    }
    override fun onDestroy(){
        browser?.destroy()
        browser=null
        super.onDestroy()
    }
}
