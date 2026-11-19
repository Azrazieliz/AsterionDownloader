package com.azrael.pixivdumpsync

import java.util.Locale

/**
 * The Control Center includes every module in its totals. The live file
 * monitor displays ONLY real, currently-active non-image original transfers.
 * Image artwork/backfill activity remains visible in per-module counters.
 */
internal object DownloadOverviewPolicy {
    /** Historical done state is inventory, not a valid saved-file receipt.
     * Missing MediaStore videos move from saved to needs-attention without
     * changing the original transfer row or deleting any media. */
    data class VideoCounts(val saved:Int,val failed:Int)
    fun reconciledVideoCounts(done:Int,failed:Int,
                              missingOriginals:Int):VideoCounts {
        require(done>=0 && failed>=0 && missingOriginals in 0..done)
        return VideoCounts(done-missingOriginals,failed+missingOriginals)
    }

    private val imageExtensions=setOf("jpg","jpeg","png","gif","webp",
        "bmp","avif","heic","heif","tif","tiff","svg","jxl")
    private val nonImageExtensions=setOf("mp4","mkv","webm","mov","avi",
        "mp3","flac","m4a","wav","ogg","zip","7z","rar","tar","gz",
        "cbz","cbr","epub","pdf","apk","exe","iso","torrent","blend",
        "fbx","obj","glb","gltf","pmx","pmd","vmd","vrm")
    private val imageModules=setOf("pixiv","x","gallery","cosplay",
        "images","artwork","illustrations","kyora","kuroha")

    fun isImage(source:String,filename:String,mime:String):Boolean {
        val type=mime.substringBefore(';').trim().lowercase(Locale.ROOT)
        if(type.startsWith("image/"))return true
        val raw=filename.substringBefore('?').substringBefore('#')
        val ext=raw.substringAfterLast('.',missingDelimiterValue="")
            .lowercase(Locale.ROOT)
        if(ext in imageExtensions)return true
        if(ext in nonImageExtensions)return false
        return source.trim().lowercase(Locale.ROOT) in imageModules
    }

    /** A progress percentage is a COMPLETE-FILE denominator, never a
     * publisher resolver stage. No value is returned for unknown length.
     */
    fun hlsCompletion(detail:String?):Int? {
        val numbers=Regex("""^([0-9]{1,7})/([1-9][0-9]{0,7}) fragments$""")
            .matchEntire(detail?.trim().orEmpty())?:return null
        val complete=numbers.groupValues[1].toLongOrNull()?:return null
        val expected=numbers.groupValues[2].toLongOrNull()?:return null
        if(expected<=0L || complete>expected)return null
        // Fragment completion alone is NOT a verified playable file.
        // Reserve 100% for the final validated MediaStore receipt; HLS
        // still requires ordered assembly, track checks and publication.
        return (complete*100L/expected).toInt().coerceAtMost(95)
    }

    fun isLiveFile(state:String,source:String,filename:String,mime:String)=
        state=="ACTIVE" && !isImage(source,filename,mime)
}
