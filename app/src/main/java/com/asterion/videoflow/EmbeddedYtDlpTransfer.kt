package com.asterion.videoflow

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import java.io.File
import java.io.IOException

/** Execute embedded yt_dlp directly. The existing third-party YtDlpRequest
 * command flag mapper drops output/paths and metadata flags; its response
 * also has no stdout. Typed Python options are required for correctness.
 * This adapter never bypasses site authentication or downloads a preview by
 * claiming it is a verified full original.
 */
internal object EmbeddedYtDlpTransfer {
    fun download(url:String,referer:String?,cookies:File?,proxy:String?,
                 directory:File,outputTemplate:String,format:String):Int {
        require(directory.isDirectory && directory.canWrite()){
            "Video staging directory unavailable"
        }
        val python=Python.getInstance()
        val builtins=python.getBuiltins()
        val opts=builtins.callAttr("dict")
        fun put(key:String,value:Any) {
            opts.asMap()[PyObject.fromJava(key)]=PyObject.fromJava(value)
        }
        put("outtmpl",outputTemplate)
        val paths=builtins.callAttr("dict")
        paths.asMap()[PyObject.fromJava("home")]=
            PyObject.fromJava(directory.absolutePath)
        paths.asMap()[PyObject.fromJava("temp")]=
            PyObject.fromJava(directory.absolutePath)
        opts.asMap()[PyObject.fromJava("paths")]=paths
        put("format",format)
        put("noplaylist",true)
        put("continuedl",true)
        put("overwrites",false)
        put("quiet",true)
        put("no_warnings",true)
        put("cachedir",false)
        put("retries",2)
        put("fragment_retries",3)
        put("socket_timeout",20)
        put("concurrent_fragment_downloads",3)
        if(cookies?.isFile==true && cookies.length()>0L)
            put("cookiefile",cookies.absolutePath)
        if(!proxy.isNullOrBlank())put("proxy",proxy)
        val headers=builtins.callAttr("dict")
        headers.asMap()[PyObject.fromJava("User-Agent")]=
            PyObject.fromJava("Mozilla/5.0 (Linux; Android 17; Mobile) "+
                "AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
        if(!referer.isNullOrBlank())
            headers.asMap()[PyObject.fromJava("Referer")]=
                PyObject.fromJava(referer)
        opts.asMap()[PyObject.fromJava("http_headers")]=headers
        val ydl=python.getModule("yt_dlp").callAttr("YoutubeDL",opts)
        try{
            val urls=builtins.callAttr("list")
            urls.callAttr("append",url)
            val result=ydl.callAttr("download",urls)
            return result?.toString()?.toIntOrNull()
                ?:throw IOException("Video extractor returned no exit status")
        }finally{
            runCatching{ydl.callAttr("close")}
        }
    }
}
