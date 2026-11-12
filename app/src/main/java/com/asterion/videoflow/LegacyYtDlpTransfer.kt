package com.asterion.videoflow

import android.content.Context
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest
import java.io.File
import java.io.IOException

/**
 * Preserved from the original standalone VideoFlow Android application.
 * Execute the packaged downloader via the library's supported Java API
 * before falling back to the newer embedded-Python adapter.
 * Completion requires an actual media file, never only exit code zero.
 */
internal object LegacyYtDlpTransfer {
    @Volatile private var initialized=false
    @Synchronized private fun initialize(context:Context){
        if(!initialized){
            YtDlp.init(context.applicationContext)
            initialized=true
        }
    }
    fun download(context:Context,url:String,referer:String?,cookie:File?,
                 proxy:String?,directory:File,outputTemplate:String,
                 format:String,onProgress:(Int)->Unit):Int {
        require(directory.isDirectory && directory.canWrite()){
            "VideoFlow original staging directory is not writable"
        }
        initialize(context)
        val request=YtDlpRequest(url).setOutputTemplate(outputTemplate)
            .addOption("--no-playlist")
            .addOption("--continue")
            .addOption("--no-overwrites")
            .addOption("--retries","2")
            .addOption("--fragment-retries","3")
            .addOption("--socket-timeout","20")
            .addOption("-f",format)
            .addOption("--user-agent","Mozilla/5.0 (Linux; Android 17; Mobile) "+
                "AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
        if(!referer.isNullOrBlank())request.addOption("--referer",referer)
        if(cookie?.isFile==true && cookie.length()>0L)
            request.addOption("--cookies",cookie.absolutePath)
        if(!proxy.isNullOrBlank())request.addOption("--proxy",proxy)
        val result=YtDlp.execute(request){percentage,_,_->
            onProgress(percentage.toInt().coerceIn(0,95))
        }
        if(!result.isSuccess)throw IOException(
            "Original VideoFlow extractor exited with code "+result.exitCode)
        val playable=directory.listFiles()?.any{
            it.isFile && it.length()>0L &&
                it.extension.lowercase() in setOf("mp4","mkv","webm","mov","ts","m4a","mp3")
        } == true
        val incomplete=directory.listFiles()?.any{
            it.isFile && (it.name.endsWith(".part") ||
                it.name.endsWith(".ytdl"))
        } == true
        if(!playable || incomplete)throw IOException(
            "Original VideoFlow extractor reported completion without a finished media file")
        return 0
    }
}
