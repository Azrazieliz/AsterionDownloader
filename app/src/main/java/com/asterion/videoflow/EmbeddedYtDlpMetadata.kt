package com.asterion.videoflow

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import org.json.JSONObject
import java.io.File
import java.net.URI

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
    /** Resolve one selected public first-party MP4 from yt-dlp's REAL
     * HTML5MediaEmbed formats, rather than handing its extensionless URL back
     * to the failing generic Python download path. Does NOT download media.
     * Signed URLs are retained only in memory and never logged/persisted.
     */
    fun firstPartyMp4(url:String,referer:String?,cookies:File?,
                      proxy:String?):String? {
        if(!FreePornVideosOriginalSource.selectedPage(url))return null
        val python=Python.getInstance()
        val options=python.getBuiltins().callAttr("dict")
        val fields=options.asMap()
        fun put(key:String,value:Any){
            fields[PyObject.fromJava(key)]=PyObject.fromJava(value)
        }
        put("skip_download",true)
        put("noplaylist",true)
        put("quiet",true)
        put("no_warnings",true)
        put("socket_timeout",20)
        put("cachedir",false)
        if(cookies?.isFile==true && cookies.length()>0L)
            put("cookiefile",cookies.absolutePath)
        if(!proxy.isNullOrBlank())put("proxy",proxy)
        val headers=python.getBuiltins().callAttr("dict")
        headers.asMap()[PyObject.fromJava("User-Agent")]=PyObject.fromJava(
            "Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
        if(!referer.isNullOrBlank())
            headers.asMap()[PyObject.fromJava("Referer")]=PyObject.fromJava(referer)
        fields[PyObject.fromJava("http_headers")]=headers
        val ydl=python.getModule("yt_dlp").callAttr("YoutubeDL",options)
        try {
            val info=ydl.callAttr("extract_info",url,false)?:return null
            val formats=info.callAttr("get","formats")?.asList()?:return null
            fun field(format:PyObject,key:String):String =
                format.callAttr("get",key)?.toString().orEmpty()
            return formats.mapNotNull{format->
                val candidate=field(format,"url")
                val ext=field(format,"ext")
                val protocol=field(format,"protocol")
                val codec=field(format,"vcodec")
                if(ext!="mp4" || protocol !in setOf("http","https") ||
                   codec.equals("none",true) ||
                   !FreePornVideosOriginalSource.firstPartyFormat(url,candidate))
                    null
                else (field(format,"height").toIntOrNull()?:0) to candidate
            }.maxByOrNull{it.first}?.second
        }finally{
            runCatching{ydl.callAttr("close")}
        }
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
        // Metadata inspection must identify itself exactly like the real
        // transfer engine. The previous default Python User-Agent disagreed
        // with the Android player session and could trigger provider HTTP 403.
        val headers=python.getBuiltins().callAttr("dict")
        headers.asMap()[PyObject.fromJava("User-Agent")]=PyObject.fromJava(
            "Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 "+
            "Chrome/131.0 Mobile Safari/537.36")
        if(!referer.isNullOrBlank())
            headers.asMap()[PyObject.fromJava("Referer")]=PyObject.fromJava(referer)
        fields[PyObject.fromJava("http_headers")]=headers
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
