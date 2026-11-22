package com.asterion.videoflow

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/** Native HTTP lane for *direct* media files. yt-dlp still resolves webpage
 * players and manifests; this lane only streams real original response bytes.
 * No external shell, browser wrapper, guessed progress, or secret cross-host
 * cookie forwarding. Distinct VideoDb leases allow two independent processes
 * to run this lane concurrently.
 */
internal object NativeMediaDownloader {
    private val supported=Regex("""(?i)\.(?:mp4|m4v|mkv|webm|mov|ts)/?$""")
    internal const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
    fun isDirectMediaUrl(url:String):Boolean {
        val uri=runCatching{URI(url)}.getOrNull()?:return false
        return uri.scheme in setOf("https","http") && uri.host!=null &&
            uri.userInfo==null && supported.containsMatchIn(uri.path.orEmpty())
    }

    /** Direct, concrete video files are ordinary HTTP transfers regardless
     * of the source category. Hentai watch pages still use their own extractor.
     */
    fun isNativeEligible(sourceUrl:String,mediaUrl:String,verifiedMp4:Boolean=false):Boolean =
        !SiteSpecificOriginalSources.isHosterPage(mediaUrl) &&
        (if(verifiedMp4)
            FreePornVideosOriginalSource.firstPartyFormat(sourceUrl,mediaUrl) ||
                BubblecumSelectedOriginalSource.verifiedMedia(sourceUrl,mediaUrl) ||
                PimpBunnySelectedOriginalSource.verifiedMedia(sourceUrl,mediaUrl) ||
                NotFansSelectedOriginalSource.verifiedDownload(sourceUrl,mediaUrl) ||
                Rule34VideoOriginalSource.firstPartyDownload(sourceUrl,mediaUrl) ||
                ThreeDHentaiSignedOriginal.firstPartyFormat(sourceUrl,mediaUrl) ||
                (FullPornerOriginalSource.selectedPage(sourceUrl) &&
                    FullPornerOriginalSource.verifiedMediaHost(mediaUrl))
         else isDirectMediaUrl(mediaUrl)) && runCatching{
            val uri=URI(sourceUrl)
            uri.scheme in setOf("http","https") && uri.host!=null
        }.getOrDefault(false)

    /** RFC 9110 partial response must cover the entire remaining resource.
     * A publisher can respond 206 to a request without Range, or supply a
     * partial preview. Neither response is a complete saved original.
     */
    internal data class FullRange(val start:Long,val end:Long,val total:Long)
    internal fun completeRange(value:String,offset:Long):FullRange? {
        val match=Regex("""bytes\s+([0-9]+)-([0-9]+)/([0-9]+)""",
            RegexOption.IGNORE_CASE).matchEntire(value.trim())?:return null
        val start=match.groupValues[1].toLongOrNull()?:return null
        val end=match.groupValues[2].toLongOrNull()?:return null
        val total=match.groupValues[3].toLongOrNull()?:return null
        if(offset<0L || total<=0L || start!=offset || end<start ||
            end!=total-1L)return null
        return FullRange(start,end,total)
    }

    /** Accept a contiguous response chunk, including a server-limited 206.
     * Publication still requires every byte through total-1. */
    internal fun contiguousRange(value:String,offset:Long):FullRange? {
        val match=Regex("""bytes\s+([0-9]+)-([0-9]+)/([0-9]+)""",
            RegexOption.IGNORE_CASE).matchEntire(value.trim())?:return null
        val start=match.groupValues[1].toLongOrNull()?:return null
        val end=match.groupValues[2].toLongOrNull()?:return null
        val total=match.groupValues[3].toLongOrNull()?:return null
        if(offset<0L || total<=0L || start!=offset || end<start ||
            end>=total)return null
        return FullRange(start,end,total)
    }

    fun download(address:String,referer:String?,directory:File,
                 cookieHeader:String?=null,filenameStem:String="video",
                 proxy:java.net.Proxy?=null,progress:(Int)->Unit={},
                 cancelled:()->Boolean={false},verifiedMp4:Boolean=false):File {
        require(if(verifiedMp4)address.startsWith("https://") &&
            runCatching{val uri=URI(address)
                uri.userInfo==null && uri.port==-1 &&
                (uri.host.orEmpty().lowercase().removePrefix("www.")==
                    "freepornvideos.xxx" ||
                    BubblecumSelectedOriginalSource.isPublisherFormat(address) ||
                    PimpBunnySelectedOriginalSource.isPublisherFormat(address) ||
                    NotFansSelectedOriginalSource.isPublisherFormat(address) ||
                    Rule34VideoOriginalSource.isPublisherMediaUrl(address) ||
                    ThreeDHentaiSignedOriginal.isSignedMediaUrl(address) ||
                    FullPornerOriginalSource.verifiedMediaHost(address))
            }.getOrDefault(false)
            else isDirectMediaUrl(address) &&
                !SiteSpecificOriginalSources.isHosterPage(address)){
            "Not a verified first-party direct original media URL"
        }
        require(directory.isDirectory && directory.canWrite()){
            "Native transfer staging directory is unavailable"
        }
        // Confirmed 17 Nov on an exact selected 93,610,328-byte original:
        // one long connection stalled; 8 lanes caused HTTP 503; exactly two
        // verified 4MiB Range lanes completed in 227.6 seconds. This
        // provider-only path keeps SHA/overlap ledger receipts on restart.
        if(verifiedMp4 && FullPornerOriginalSource.verifiedMediaHost(address))
            return FullPornerParallelOriginal.download(address,referer,directory,
                filenameStem,proxy,progress,cancelled)
        val ext=if(verifiedMp4)"mp4" else
            URI(address).path.trimEnd('/').substringAfterLast('.')
                .lowercase(Locale.ROOT)
        val stem=filenameStem.replace(
            Regex("""[\\/:*?"<>|\p{Cntrl}]"""),"_")
            .trim().take(140).ifBlank{"video"}
        val destination=File(directory,"$stem.$ext")
        val partial=File(directory,"native_original.$ext.part")
        val validator=File(directory,"native_original.$ext.etag")
        val origin=File(directory,"native_original.$ext.origin")
        val originHash=MessageDigest.getInstance("SHA-256")
            .digest(address.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val receipt=File(directory,"$stem.$ext.receipt")
        // A prior APK could leave a nonempty but truncated/corrupt staged
        // destination. Existence alone is NEVER a completion proof.
        // A SHA/length receipt proves the staged file's integrity, not
        // which candidate supplied it. Bind final-file reuse to the exact
        // selected source URL as well, or another scene may be returned.
        val matchesExistingSource=runCatching {
            origin.isFile && origin.readText().trim()==originHash
        }.getOrDefault(false)
        if(destination.isFile && matchesExistingSource &&
            NativeMediaReceipt.matches(destination,receipt))
            return destination
        // Native candidates of one video can share this staging directory.
        // Never resume another candidate's bytes (nor a pre-upgrade partial
        // without an origin binding) merely because the extension matches.
        val matchesOrigin=runCatching {
            origin.isFile && origin.readText().trim()==originHash
        }.getOrDefault(false)
        if(partial.isFile && partial.length()>0L && !matchesOrigin){
            partial.delete()
            validator.delete()
            origin.delete()
        }
        var current=address
        var redirects=0
        var continuationTotal:Long?=null
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
            val offset=partial.length().coerceAtLeast(0L)
            // Many publisher CDNs omit ETag. Compare the tail of the saved
            // file with an overlapping ranged response before appending.
            // This safely resumes a timed-out response without guessing media
            // identity, and never uses stale bytes from another URL.
            val ifRange=runCatching {
                if(validator.isFile)validator.readText().trim() else ""
            }.getOrDefault("")
            // Even an ETag cannot prove that a buggy CDN honored If-Range.
            // Compare the final 64 saved bytes for EVERY resume, including
            // ETag responses, so a changed source never becomes a mixed MP4.
            val overlap=if(offset>0L) minOf(offset,64L).toInt() else 0
            val requestStart=offset-overlap
            val conn=(if(proxy==null)URL(current).openConnection()
                else URL(current).openConnection(proxy)) as HttpURLConnection
            conn.instanceFollowRedirects=false
            conn.connectTimeout=15_000
            conn.readTimeout=30_000
            conn.setRequestProperty("Accept","video/*,application/octet-stream;q=0.9")
            conn.setRequestProperty("Accept-Encoding","identity")
            conn.setRequestProperty("User-Agent",USER_AGENT)
            if(!referer.isNullOrBlank() && URI(referer).scheme in setOf("http","https"))
                conn.setRequestProperty("Referer",referer)
            if(sameHost && !cookieHeader.isNullOrBlank())
                conn.setRequestProperty("Cookie",cookieHeader)
            if(offset>0L){
                conn.setRequestProperty("Range","bytes=$requestStart-")
                if(ifRange.isNotBlank())conn.setRequestProperty("If-Range",ifRange)
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
                // Some CDNs cap a 206 response to one contiguous chunk.
                // Demand the next exact offset until the TOTAL is received.
                // A preview, repeated chunk or a 200 ignoring Range is not complete.
                val fullRange=if(status==206)
                    contiguousRange(contentRange,requestStart)
                        ?:throw IOException(
                            "Media provider returned an incomplete or mismatched byte range")
                    else null
                if(continuationTotal!=null &&
                    (fullRange==null || fullRange.total!=continuationTotal))
                    throw IOException("Media source changed or ignored continued byte range")
                val append=status==206 && offset>0L
                if(!append){partial.delete();validator.delete()}
                origin.writeText(originHash)
                val etag=conn.getHeaderField("ETag")?.trim().orEmpty()
                // Misconfigured hosters sometimes reply 206 even though the
                // If-Range validator no longer matches. Fail closed and
                // preserve the ability to restart instead of corrupting
                // downloaded bytes (the entire payload may have changed).
                if(append && ifRange.isNotBlank() &&
                    etag.isNotBlank() && etag!=ifRange){
                    partial.delete()
                    validator.delete()
                    origin.delete()
                    throw IOException(
                        "Media resume ETag changed; discarded mismatched partial")
                }
                if(etag.isNotBlank())validator.writeText(etag)
                else validator.delete()
                val expected=fullRange?.let{it.end-it.start+1L}
                    ?:conn.contentLengthLong.takeIf{it>=0L}
                var written=0L
                var consumed=0L
                conn.inputStream.use{input->
                    if(append && overlap>0) {
                        // Revalidate actual publisher bytes before appending.
                        // An MP4 with the same size can still be a different
                        // video after an expiring CDN redirect or player change.
                        val previous=ByteArray(overlap)
                        java.io.RandomAccessFile(partial,"r").use {
                            it.seek(requestStart)
                            it.readFully(previous)
                        }
                        val received=ByteArray(overlap)
                        var read=0
                        while(read<overlap){
                            val n=input.read(received,read,overlap-read)
                            if(n<0)throw IOException(
                                "Media resume overlap was truncated")
                            if(n>0)read+=n
                        }
                        if(!previous.contentEquals(received)){
                            partial.delete()
                            validator.delete()
                            origin.delete()
                            throw IOException(
                                "Media resume source changed; discarded mismatched partial")
                        }
                        consumed=overlap.toLong()
                    }
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
                            consumed+=n
                            val whole=fullRange?.total?:expected?.plus(offset)
                            if(whole!=null && whole>0L)
                                progress((((offset+written)*95L)/whole)
                                    .toInt().coerceIn(1,95))
                        }
                        output.flush()
                    }
                }
                if(expected!=null && consumed!=expected)
                    throw IOException("Incomplete native transfer: $consumed of $expected bytes")
                if(fullRange!=null && partial.length()!=fullRange.end+1L)
                    throw IOException("Incomplete HTTP 206 chunk: "+
                        partial.length()+" of "+(fullRange.end+1L)+" bytes")
                if(partial.length()<=0L)
                    throw IOException("Native provider supplied zero original bytes")
                if(fullRange!=null && fullRange.end<fullRange.total-1L){
                    continuationTotal=fullRange.total
                    continue
                }
                if(fullRange!=null && partial.length()!=fullRange.total)
                    throw IOException("Incomplete original: "+
                        partial.length()+" of "+fullRange.total+" bytes")
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
                // Persist a length+SHA256 receipt only after the exact full
                // response was downloaded and the staged file finalized.
                // Interrupted receipt writes force revalidation, never reuse.
                NativeMediaReceipt.write(destination,receipt)
                validator.delete()
                // Keep only this SHA-256 source binding (never a private URL)
                // so a future candidate cannot reuse another scene's file.
                return destination
            }finally{
                conn.disconnect()
            }
        }
    }
}
