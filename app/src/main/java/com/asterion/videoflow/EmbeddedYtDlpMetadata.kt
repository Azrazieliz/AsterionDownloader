package com.asterion.videoflow

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import org.json.JSONObject
import java.io.File

/** Uses the Python interpreter already bundled by yt-dlp-android 2.0.2.
 * The upstream YtDlpResponse has no stdout and its CLI parser silently
 * drops metadata flags. This adapter calls YoutubeDL.extract_info with
 * download=false and returns typed metadata, never fabricated JSON.
 */
internal object EmbeddedYtDlpMetadata {
    fun checkRuntime(){
        val py=Python.getInstance()
        val module=py.getModule("yt_dlp")
        require(module.get("YoutubeDL")!=null){"Bundled yt_dlp module missing"}
    }
    fun inspect(url:String,referer:String?,cookies:File?,proxy:String?):JSONObject{
        val python=Python.getInstance()
        val opts=python.getBuiltins().callAttr("dict")
        val fields=opts.asMap()
        fun put(k:String,value:Any){
            fields[PyObject.fromJava(k)]=PyObject.fromJava(value)
        }
        put("skip_download",true)
        put("noplaylist",true)
        put("quiet",true)
        put("no_warnings",true)
        put("socket_timeout",20)
        put("cachedir",false)
        if(cookies!=null && cookies.isFile)put("cookiefile",cookies.absolutePath)
        if(!proxy.isNullOrBlank())put("proxy",proxy)
        if(!referer.isNullOrBlank()){
            val headers=python.getBuiltins().callAttr("dict")
            headers.asMap()[PyObject.fromJava("Referer")]=PyObject.fromJava(referer)
            fields[PyObject.fromJava("http_headers")]=headers
        }
        val ydl=python.getModule("yt_dlp").callAttr("YoutubeDL",opts)
        try{
            // This positional false is the Python download flag. No data is
            // transferred until the separate real-transfer stage begins.
            val info=ydl.callAttr("extract_info",url,false)
                ?:error("Provider supplied no video metadata")
            fun property(key:String):String?=info.callAttr("get",key)?.toString()
            val title=property("title").orEmpty().take(240)
            val duration=property("duration")?.toDoubleOrNull()
            val result=JSONObject()
            result.put("title",title)
            property("id")?.let{result.put("id",it.take(120))}
            if(duration!=null && duration.isFinite() && duration>0.0)
                result.put("duration",duration)
            return result
        }finally{
            runCatching{ydl.callAttr("close")}
        }
    }
}
