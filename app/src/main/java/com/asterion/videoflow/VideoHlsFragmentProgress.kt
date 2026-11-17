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
                it.length() in 15L..16_384L
        }
        // RoshyTV and similar HLS publishers may expose distinct video
        // and audio fragment tracks. Reject missing/invalid checkpoints,
        // but permit 2-4 complete track counters and report the *slowest*
        // track's completion percentage. Never sum mismatched fragment totals
        // or claim that the full video is finished before muxing.
        if(records.size !in 1..4)return null
        val samples=records.map { checkpoint ->
            runCatching{parse(checkpoint.readText(Charsets.UTF_8))}
                .getOrNull()?:return null
        }
        return samples.minByOrNull{it.percent}
    }
}
