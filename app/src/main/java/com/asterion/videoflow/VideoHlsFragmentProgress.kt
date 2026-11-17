package com.asterion.videoflow

import java.io.File
import org.json.JSONObject

/**
 * yt-dlp's *actual bundled* fragment.py serializes:
 * {"downloader":{"current_fragment":{"index":N},"fragment_count":M}}
 * after completed HLS fragments. The earlier callbacks sometimes emitted no
 * percent even while the on-disk .part file was growing.
 *
 * This is a verified count of completed fragments, not an estimate of bytes
 * remaining and never a final success receipt. Unavailable/ambiguous progress
 * yields null rather than invented completion.
 */
internal object VideoHlsFragmentProgress {
    data class Sample(val completed:Int,val total:Int) {
        val percent:Int get()=((completed.toLong()*95L)/total)
            .toInt().coerceIn(0,95)
        val description:String get()="$completed/$total fragments"
    }
    fun parse(data:String):Sample? = runCatching {
        val state=JSONObject(data).optJSONObject("downloader")
            ?:return@runCatching null
        val fragment=state.optJSONObject("current_fragment")
            ?:return@runCatching null
        val complete=fragment.optInt("index",-1)
        val count=state.optInt("fragment_count",-1)
        if(count !in 1..2_000_000 || complete !in 1..count)
            return@runCatching null
        Sample(complete,count)
    }.getOrNull()

    fun inspect(directory:File):Sample? {
        if(!directory.isDirectory || VideoPayloadBytes.count(directory)<=0L)
            return null
        val records=directory.listFiles().orEmpty().filter {
            it.isFile && it.name.endsWith(".ytdl",ignoreCase=true) &&
                it.length() in 15..16_384
        }
        // Multiple simultaneous fragment outputs may refer to different
        // media tracks; do not splice their totals or report false progress.
        if(records.size!=1)return null
        return runCatching{parse(records.single().readText(Charsets.UTF_8))}
            .getOrNull()
    }
}
