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
        // HentaiHaven's selected HLS currently declares one audio rendition
        // with a misleading "audio-English" label. The owner confirms the
        // selected recording has no English dub. Keep source audio and embed
        // the publisher's English subtitles; don't block all downloads on
        // a language-label heuristic. Never claim speech verified from tags.
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
            // Embed the actual publisher's English subtitle rendition in
            // the final MKV rather than discarding its VTT sidecar. Spoken
            // language cannot be established from the audio track label.
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
        // The owner's selected xChina HLS received ~3 MB then got
        // 401/403 during concurrent fragment requests. Keep normal signed
        // headers and the same owner/playlist; use sequential fragments on
        // this exact matched source to avoid request bursts and RAM spikes.
        // This cannot override a genuine site authorization denial.
        put("concurrent_fragment_downloads",
            if(VideoHlsDispatch.isSelectedXChinaHls(referer,url))1 else 3)
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
