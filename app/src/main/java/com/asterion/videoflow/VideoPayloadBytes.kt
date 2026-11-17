package com.asterion.videoflow

import java.io.File

/** Count only saved/partial media payloads, not yt-dlp bookkeeping.
 * A growing .etag, .ytdl or JSON sidecar must not turn a stalled download
 * into the misleading "Receiving original" state.
 */
internal object VideoPayloadBytes {
    private val media = setOf("mp4","m4v","mkv","webm","mov","ts",
        "m4a","mp3","flac","opus","aac","ogg","3gp")
    fun isPayload(filename:String):Boolean {
        val name=filename.lowercase(java.util.Locale.ROOT)
        // yt-dlp's HLS/DASH fragment workers write .part-Frag<integer>
        // while individual segments are arriving. Previous accounting
        // ignored those bytes, declaring a live stream stalled at 0 and
        // cancelling otherwise healthy Jav Guru/Pornhub transfers.
        val fragment=name.replace(Regex("""(?i)\.part-frag[0-9]{1,10}$"""),".part")
        if(fragment.endsWith(".part")) {
            val withoutPart=fragment.removeSuffix(".part")
            return media.any { withoutPart.endsWith("."+it) } ||
                media.any { withoutPart.contains("."+it+".") }
        }
        return media.any { name.endsWith("."+it) }
    }
    fun count(directory:File):Long =
        directory.listFiles().orEmpty().asSequence()
            .filter{it.isFile && isPayload(it.name)}
            .sumOf{it.length().coerceAtLeast(0L)}
}
