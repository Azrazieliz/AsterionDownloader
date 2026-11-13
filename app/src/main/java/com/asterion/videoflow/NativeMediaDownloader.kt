package com.asterion.videoflow

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

/** Native HTTP lane for *direct* media files. yt-dlp still resolves webpage
 * players and manifests; this lane only streams real original response bytes.
 * No external shell, browser wrapper, guessed progress, or secret cross-host
 * cookie forwarding. Distinct VideoDb leases allow two independent processes
 * to run this lane concurrently.
 */
internal object NativeMediaDownloader {
    private val supported=Regex("""(?i)\.(?:mp4|m4v|mkv|webm|mov|ts)/?$""")
    fun isDirectMediaUrl(url:String):Boolean {
        val uri=runCatching{URI(url)}.getOrNull()?:return false
        return uri.scheme in setOf("https","http") && uri.host!=null &&
            uri.userInfo==null && supported.containsMatchIn(uri.path.orEmpty())
    }

    /** Direct, concrete video files are ordinary HTTP transfers regardless
     * of the source category. Hentai watch pages still use their own extractor.
     */
    fun isNativeEligible(sourceUrl:String,mediaUrl:String,verifiedMp4:Boolean=false):Boolean =
        (isDirectMediaUrl(mediaUrl) ||
         (verifiedMp4 && FreePornVideosOriginalSource.firstPartyFormat(
             sourceUrl,mediaUrl))) && runCatching{
            val uri=URI(sourceUrl)
            uri.scheme in setOf("http","https") && uri.host!=null
        }.getOrDefault(false)

    fun download(address:String,referer:String?,directory:File,
                 cookieHeader:String?=null,filenameStem:String="video",
                 proxy:java.net.Proxy?=null,progress:(Int)->Unit={},
                 cancelled:()->Boolean={false},verifiedMp4:Boolean=false):File {
        require(isDirectMediaUrl(address) ||
            (verifiedMp4 && address.startsWith("https://") &&
                runCatching{val uri=URI(address)
                    uri.userInfo==null && uri.port==-1 &&
                    uri.host.orEmpty().lowercase().removePrefix("www.")==
                        "freepornvideos.xxx"
                }.getOrDefault(false))){
            "Not a verified first-party direct original media URL"
        }
        require(directory.isDirectory && directory.canWrite()){
            "Native transfer staging directory is unavailable"
        }
        val ext=if(verifiedMp4)"mp4" else
            URI(address).path.trimEnd('/').substringAfterLast('.')
                .lowercase(Locale.ROOT)
        val stem=filenameStem.replace(
            Regex("""[\\/:*?"<>|\p{Cntrl}]"""),"_")
            .trim().take(140).ifBlank{"video"}
        val destination=File(directory,"$stem.$ext")
        val partial=File(directory,"native_original.$ext.part")
        val validator=File(directory,"native_original.$ext.etag")
        if(destination.isFile && destination.length()>0L)return destination
        var current=address
        var redirects=0
        while(true){
            if(cancelled())throw java.util.concurrent.CancellationException(
                "Native transfer paused")
            val uri=URI(current)
            require(uri.scheme in setOf("https","http") &&
                uri.host!=null && uri.userInfo==null){
                "Native media redirect is not HTTP(S)"
            }
            val originalHost=URI(address).host
            val sameHost=originalHost.equals(uri.host,ignoreCase=true)
            val resumed=partial.length()>0L && validator.isFile &&
                validator.readText().isNotBlank()
            val offset=if(resumed)partial.length() else 0L
            val conn=(if(proxy==null)URL(current).openConnection()
                else URL(current).openConnection(proxy)) as HttpURLConnection
            conn.instanceFollowRedirects=false
            conn.connectTimeout=15_000
            conn.readTimeout=30_000
            conn.setRequestProperty("Accept","video/*,application/octet-stream;q=0.9")
            conn.setRequestProperty("Accept-Encoding","identity")
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36")
            if(!referer.isNullOrBlank() && URI(referer).scheme in setOf("http","https"))
                conn.setRequestProperty("Referer",referer)
            if(sameHost && !cookieHeader.isNullOrBlank())
                conn.setRequestProperty("Cookie",cookieHeader)
            if(offset>0L){
                conn.setRequestProperty("Range","bytes=$offset-")
                conn.setRequestProperty("If-Range",validator.readText().trim())
            }
            try{
                val status=conn.responseCode
                if(status in 300..399){
                    if(redirects++>=5)throw IOException("Too many media redirects")
                    val location=conn.getHeaderField("Location")
                        ?:throw IOException("Media redirect has no Location")
                    current=URI(current).resolve(location).toString()
                    continue
                }
                if(status==401 || status==403)
                    throw IOException("Native media HTTP $status; provider account access required")
                if(status==429)throw IOException(
                    "Native media HTTP 429; retry after "+
                        (conn.getHeaderField("Retry-After")?:"server cooldown"))
                if(status !in setOf(200,206))
                    throw IOException("Native media HTTP $status")
                val mime=conn.contentType.orEmpty().substringBefore(';')
                    .trim().lowercase(Locale.ROOT)
                if(verifiedMp4 && mime!="video/mp4")
                    throw IOException("Verified first-party URL did not serve video/mp4")
                if(mime !in setOf("application/octet-stream",
                    "binary/octet-stream","application/x-matroska") &&
                    !mime.startsWith("video/")){
                    throw IOException("Media URL returned $mime instead of a video file")
                }
                val contentRange=conn.getHeaderField("Content-Range").orEmpty()
                val matchingRange=status==206 && offset>0L &&
                    contentRange.startsWith("bytes $offset-")
                if(status==206 && offset>0L && !matchingRange)
                    throw IOException("Media provider returned a mismatched byte range")
                val append=matchingRange
                if(!append){partial.delete();validator.delete()}
                val etag=conn.getHeaderField("ETag")?.trim().orEmpty()
                if(etag.isNotBlank())validator.writeText(etag)
                else validator.delete()
                val expected=conn.contentLengthLong
                    .takeIf{it>=0L}
                var written=0L
                conn.inputStream.use{input->
                    FileOutputStream(partial,append).buffered(131_072).use{output->
                        val buffer=ByteArray(131_072)
                        while(true){
                            if(cancelled() || Thread.currentThread().isInterrupted)
                                throw java.util.concurrent.CancellationException(
                                    "Native transfer paused")
                            val n=input.read(buffer)
                            if(n<0)break
                            if(n==0)continue
                            output.write(buffer,0,n)
                            written+=n
                            if(expected!=null && expected>0L)
                                progress((((offset+written)*95L)/(offset+expected))
                                    .toInt().coerceIn(1,95))
                        }
                        output.flush()
                    }
                }
                if(expected!=null && written!=expected)
                    throw IOException("Incomplete native transfer: $written of $expected bytes")
                if(partial.length()<=0L)
                    throw IOException("Native provider supplied zero original bytes")
                if(verifiedMp4){
                    val magic=ByteArray(12)
                    val read=partial.inputStream().use{it.read(magic)}
                    if(read<12 || !String(magic,4,4,
                            Charsets.US_ASCII).equals("ftyp"))
                        throw IOException(
                            "Selected provider returned non-MP4 or invalid first media bytes")
                }
                if(destination.exists() && !destination.delete())
                    throw IOException("Cannot replace staged media file")
                check(partial.renameTo(destination)){
                    "Cannot finalize downloaded native original media"
                }
                validator.delete()
                return destination
            }finally{
                conn.disconnect()
            }
        }
    }
}
