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
        if(name.endsWith(".part")) {
            val withoutPart=name.removeSuffix(".part")
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
