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
        // Selected HentaiHaven publishes separate audio and video.
        // Its current manifest labels its only audio "audio-English" (en),
        // and has English subtitles, but declares NO Japanese audio.
        // That does not certify VOSTA: never silently select English-
        // labelled audio or claim that subtitles change a dub to original.
        // Japanese-original voice + English subtitles is required, with
        // any unverified/English-only episode held until actual evidence.
        // This restriction applies solely to the exact selected episode.
        val havenEpisodeHls=!referer.isNullOrBlank() &&
            HentaiHavenEpisodeSource.isEpisode(referer) &&
            HentaiHavenEpisodeSource.isApprovedHls(url)
        // PC complete-file verification succeeded using separate HLS video
        // and audio with MPEG-TS fragments and final Matroska mux. Scope
        // identically to the exact selected Pornhub watch + publisher CDN.
        val pornhubSelectedHls=!referer.isNullOrBlank() &&
            PornhubOriginalSource.selectedHlsTransfer(referer,url)
        put("format",when {
            havenEpisodeHls -> HentaiVostaPolicy.selectedAudioFormat
            pornhubSelectedHls -> "bv+ba/b"
            else -> format
        })
        if(havenEpisodeHls || pornhubSelectedHls){
            put("hls_use_mpegts",true)
            put("merge_output_format","mkv")
        }
        if(havenEpisodeHls){
            // Subtitles belong in the playable MKV, not a lost VTT sidecar
            // from the temporary staging directory. Require the exact
            // English subtitle stream from the selected episode; a missing
            // caption or original-language audio must never be a receipt.
            put("writesubtitles",true)
            put("subtitlesformat","best")
            val languages=builtins.callAttr("list")
            languages.callAttr("append",
                HentaiVostaPolicy.englishSubtitleLanguage)
            opts.asMap()[PyObject.fromJava("subtitleslangs")]=languages
            val postprocessors=builtins.callAttr("list")
            val embed=builtins.callAttr("dict")
            embed.asMap()[PyObject.fromJava("key")]=
                PyObject.fromJava("FFmpegEmbedSubtitle")
            postprocessors.callAttr("append",embed)
            opts.asMap()[PyObject.fromJava("postprocessors")]=postprocessors
        }
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
