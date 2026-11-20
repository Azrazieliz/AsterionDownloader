package com.asterion.videoflow

import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.max

/**
 * ONLY the owner-selected Jav Guru JK -> Maxstream finalized HLS original.
 *
 * PC verified 751/751 first-party 720p AES-128 MPEG-TS fragments, full
 * 7503.262s video+audio and SHA. The generic bundled yt-dlp HTTP path
 * returned 403 for the same signed CDN, while ordinary direct HTTPS with
 * the publisher's own playlist/key/segment links completed successfully.
 *
 * This is standard authorized HLS playback, not key recovery, guessing
 * tokens, changing app routing or falling through to unrelated videos.
 * Every fragment is bound to the exact selected Maxstream CDN and ENDLIST
 * playlist. All other providers remain on their existing transfer engines.
 */
internal object JavGuruJkNativeHls {
    private const val CDN="s4.maxstream.org"
    private const val MAX_SEGMENTS=1800
    private const val MAX_FRAGMENT=15_000_000
    private const val MAX_PLAYLIST=300_000
    private val selectedArticle=Regex(
        """^/[0-9]{4,10}/[A-Za-z0-9][A-Za-z0-9_-]{3,240}/?$""")
    private val selectedPlayer=Regex(
        """^/(?:e|embed|v)/[A-Za-z0-9_-]{5,180}/?$""")

    private fun parsed(raw:String):URI?=runCatching{URI(raw)}.getOrNull()
        ?.takeIf{it.scheme=="https" && it.userInfo==null &&
            it.host!=null && it.port==-1 && it.rawFragment==null}

    /** Native-only queue selection must still be an exact JAV Guru post,
     * never a listing, shortcut, recommendation or spoofed site. */
    fun selectedArticleForNative(article:String):Boolean {
        val p=parsed(article)?:return false
        return p.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")=="jav.guru" &&
            selectedArticle.matches(p.path.orEmpty())
    }

    fun eligible(article:String,source:String,referer:String?):Boolean {
        if(!selectedArticleForNative(article))return false
        val h=parsed(source)?:return false
        val frame=referer?.let(::parsed)?:return false
        return h.host.equals(CDN,ignoreCase=true) &&
            h.path.orEmpty().endsWith(".m3u8",true) &&
            h.path.orEmpty().length in 12..900 &&
            !h.path.orEmpty().contains("..") &&
            !h.rawQuery.isNullOrBlank() &&
            frame.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")=="maxstream.org" &&
            selectedPlayer.matches(frame.path.orEmpty()) &&
            frame.rawQuery.isNullOrBlank() &&
            !FullLengthVideoPolicy.isExplicitPreview("",source)
    }

    private fun cdnOnly(raw:String):URI {
        val u=parsed(raw)?:throw IOException("Selected HLS URL is not trusted HTTPS")
        if(!u.host.equals(CDN,ignoreCase=true) ||
            u.path.orEmpty().contains("..") ||
            u.path.orEmpty().length !in 2..1100)
            throw IOException("Selected HLS reference left the published CDN")
        return u
    }

    private fun child(base:String,raw:String):String {
        if(raw.length !in 1..2200 || raw.contains('\r') || raw.contains('\n'))
            throw IOException("Invalid selected HLS relative source")
        val resolved=parsed(base)?.resolve(raw)?.toString()
            ?:throw IOException("Invalid selected HLS base")
        return cdnOnly(resolved).toString()
    }

    private fun fetch(url:String,referer:String,maxBytes:Int,proxy:Proxy?):ByteArray {
        cdnOnly(url)
        val conn=(if(proxy==null)URL(url).openConnection()
            else URL(url).openConnection(proxy)) as HttpURLConnection
        conn.instanceFollowRedirects=false
        conn.connectTimeout=18_000
        conn.readTimeout=32_000
        conn.setRequestProperty("User-Agent",NativeMediaDownloader.USER_AGENT)
        conn.setRequestProperty("Referer",referer)
        conn.setRequestProperty("Accept-Encoding","identity")
        try{
            if(conn.responseCode !in setOf(200,206))
                throw IOException("Selected CDN HTTP "+conn.responseCode)
            val out=ByteArrayOutputStream()
            conn.inputStream.use{input->
                val buf=ByteArray(64*1024)
                while(true){
                    val len=input.read(buf)
                    if(len<0)break
                    if(out.size()+len>maxBytes)
                        throw IOException("Selected HLS response exceeds file bounds")
                    out.write(buf,0,len)
                }
            }
            return out.toByteArray()
        } finally {conn.disconnect()}
    }

    internal data class Plan(
        val segments:List<String>,val keyUrl:String,
        val mediaSequence:Long,val explicitIv:ByteArray?,
        val durationSeconds:Double
    )

    internal fun selectedVariant(master:String,body:String):String {
        cdnOnly(master)
        if(!body.startsWith("#EXTM3U"))
            throw IOException("Selected JK master is not HLS")
        val lines=body.lines().map{it.trim()}
        val choices=ArrayList<String>()
        for(index in 0 until lines.size-1){
            val line=lines[index]
            if(!line.startsWith("#EXT-X-STREAM-INF:"))continue
            val height=Regex("""(?:^|,)RESOLUTION=[0-9]+x([0-9]+)(?:,|$)""")
                .find(line.substringAfter(":"))?.groupValues?.get(1)
                ?.toIntOrNull()
            if(height!=720)continue
            val next=lines[index+1]
            if(next.startsWith("#"))continue
            val url=child(master,next)
            if(!parsed(url)!!.path.orEmpty().endsWith(".m3u8",true))
                throw IOException("Selected JK rendition is not HLS")
            choices+=url
        }
        if(choices.distinct().size!=1 || choices.size!=1)
            throw IOException("Selected JK did not expose one original 720p VOD")
        return choices.single()
    }

    internal fun selectedPlan(variant:String,body:String):Plan {
        cdnOnly(variant)
        if(!body.startsWith("#EXTM3U") ||
            body.lines().count{it.trim()=="#EXT-X-ENDLIST"}!=1 ||
            !body.contains("#EXT-X-PLAYLIST-TYPE:VOD") ||
            listOf("#EXT-X-BYTERANGE:","#EXT-X-MAP:","#EXT-X-SESSION-KEY:",
                "#EXT-X-DISCONTINUITY").any(body::contains))
            throw IOException("Selected JK media is not finalized supported VOD")
        val keyLines=body.lines().map{it.trim()}.filter{
            it.startsWith("#EXT-X-KEY:")
        }
        if(keyLines.size!=1 || !keyLines.single().contains("METHOD=AES-128"))
            throw IOException("Selected JK did not publish one AES-128 key")
        val spec=keyLines.single().substringAfter(":")
        val keyRaw=Regex("(?:^|,)URI=\\\"([^\\\"]{1,2200})\\\"")
            .find(spec)?.groupValues?.get(1)
            ?:throw IOException("Selected JK AES key URL is missing")
        val keyUrl=child(variant,keyRaw)
        val ivText=Regex("""(?:^|,)IV=0x([a-fA-F0-9]{32})(?:,|$)""")
            .find(spec)?.groupValues?.get(1)
        val iv=ivText?.chunked(2)?.map{it.toInt(16).toByte()}?.toByteArray()
        val sequence=Regex("""(?m)^#EXT-X-MEDIA-SEQUENCE:([0-9]+)\s*$""")
            .find(body)?.groupValues?.get(1)?.toLongOrNull()?:0L
        val segments=ArrayList<String>()
        var sum=0.0
        var pending=false
        for(line in body.lines().map{it.trim()}){
            if(line.startsWith("#EXTINF:")){
                if(pending)throw IOException("Duplicated HLS duration")
                val value=line.substringAfter(':').substringBefore(',')
                    .toDoubleOrNull()
                if(value==null || !value.isFinite() || value<=0.0 ||
                    value>120.0)
                    throw IOException("Invalid selected segment duration")
                sum+=value
                pending=true
            }else if(line.isNotBlank() && !line.startsWith("#")){
                if(!pending)throw IOException("Selected segment lacks EXTINF")
                val url=child(variant,line)
                if(!parsed(url)!!.path.orEmpty().endsWith(".ts",true))
                    throw IOException("JK original segment is not MPEG-TS")
                segments+=url
                pending=false
            }
        }
        if(pending || segments.size !in 100..MAX_SEGMENTS ||
            segments.distinct().size!=segments.size ||
            sum !in 600.0..15000.0)
            throw IOException("Selected JK segment ledger or full runtime invalid")
        return Plan(segments,keyUrl,sequence,iv,sum)
    }

    private fun hex(bytes:ByteArray):String =
        bytes.joinToString(""){"%02x".format(Locale.ROOT,it.toInt() and 0xff)}
    private fun sha256(bytes:ByteArray):ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    /**
     * A signed HLS query may refresh between legitimate owner retries.
     * Reuse only fragments from the SAME selected article + player + CDN
     * paths, exact per-segment durations, sequence, IV and AES key.
     * Changed video/episode, playlist, encryption key or segment identity
     * gets a different private stage and cannot be mixed with old bytes.
     */
    internal fun stageFingerprint(article:String,media:String,frame:String,
                                  variant:String,plan:Plan,key:ByteArray,
                                  durations:List<String>):String {
        require(eligible(article,media,frame))
        require(key.size==16 && durations.size==plan.segments.size)
        val identity=buildString {
            appendLine("selected-jav-guru-jk-v1")
            appendLine(article)
            appendLine(frame)
            appendLine(cdnOnly(media).path)
            appendLine(cdnOnly(variant).path)
            appendLine(hex(sha256(key)))
            appendLine(plan.mediaSequence)
            appendLine(plan.explicitIv?.let(::hex).orEmpty())
            appendLine(plan.durationSeconds.toString())
            for(index in plan.segments.indices) {
                append(cdnOnly(plan.segments[index]).path)
                append('|').append(durations[index]).append('\n')
            }
        }
        return hex(sha256(identity.toByteArray(Charsets.UTF_8))).take(32)
    }

    private fun fileHash(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { source ->
            val buffer=ByteArray(65536)
            while(true) {
                val count=source.read(buffer)
                if(count<0)break
                digest.update(buffer,0,count)
            }
        }
        return hex(digest.digest())
    }

    /** A complete decrypted fragment is written before its SHA sidecar.
     * Missing/truncated/mismatched sidecars are not trusted on resume.
     * No publisher file is accepted from a prior different HLS stage.
     */
    internal fun reusableFragment(file:File):Boolean = runCatching {
        if(!file.isFile || file.length() !in 1504L..MAX_FRAGMENT.toLong() ||
            file.length()%188L!=0L)return@runCatching false
        // An intact receipt alone is not proof of MPEG-TS media;
        // require the same sync shape checked on initial AES decryption.
        val header=ByteArray(188*4)
        file.inputStream().use{input->
            var read=0
            while(read<header.size){
                val count=input.read(header,read,header.size-read)
                if(count<=0)return@runCatching false
                read+=count
            }
        }
        if((0..3).any{(header[it*188].toInt() and 255)!=0x47})
            return@runCatching false
        val sign=File(file.parentFile,file.name+".sha256")
        if(!sign.isFile)return@runCatching false
        val expected=sign.readText().trim()
        expected.matches(Regex("[a-f0-9]{64}")) &&
            expected==fileHash(file)
    }.getOrDefault(false)

    /** The percentage is derived from verified completed fragments, not
     * elapsed time, worker count, or an optimistic network estimate.
     */
    internal fun fragmentPercent(complete:Int,total:Int):Int {
        require(total>0 && complete in 0..total)
        return complete*90/total
    }

    private fun segmentIv(plan:Plan,index:Int):ByteArray {
        plan.explicitIv?.let{return it.copyOf()}
        val iv=ByteArray(16)
        var seq=plan.mediaSequence+index
        for(i in 15 downTo 8){
            iv[i]=(seq and 0xffL).toByte()
            seq=seq ushr 8
        }
        return iv
    }

    internal fun decodeSegment(encrypted:ByteArray,key:ByteArray,iv:ByteArray):ByteArray {
        if(key.size!=16 || iv.size!=16 || encrypted.size<1504 ||
            encrypted.size%16!=0)
            throw IOException("Selected AES-128 fragment is truncated")
        val cipher=Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),IvParameterSpec(iv))
        val block=cipher.doFinal(encrypted)
        val last=block.last().toInt() and 255
        val data=if(last in 1..16 && block.takeLast(last).all{
                (it.toInt() and 255)==last})block.copyOf(block.size-last)
            else block
        if(data.size<1504 || data.size%188!=0 ||
            (0..3).any{index->(data[index*188].toInt() and 255)!=0x47})
            throw IOException("Selected AES-128 original did not decrypt to MPEG-TS")
        return data
    }

    fun download(article:String,media:String,referer:String?,
                 directory:File,filenameStem:String,proxy:Proxy?=null,
                 progress:(Int,Long,Int,Int)->Unit={_,_,_,_->},cancelled:()->Boolean={false}):File {
        require(eligible(article,media,referer)){
            "Not the exact selected Jav Guru JK original HLS"
        }
        require(directory.isDirectory && directory.canWrite()){
            "Selected JK video staging is unavailable"
        }
        val frame=referer!!
        fun checkCancel(){
            if(cancelled() || Thread.currentThread().isInterrupted)
                throw CancellationException("Selected JK download paused")
        }
        checkCancel()
        val master=String(fetch(media,frame,MAX_PLAYLIST,proxy),Charsets.UTF_8)
        val variant=selectedVariant(media,master)
        val vod=String(fetch(variant,media,MAX_PLAYLIST,proxy),Charsets.UTF_8)
        val plan=selectedPlan(variant,vod)
        val key=fetch(plan.keyUrl,variant,128,proxy)
        if(key.size!=16)throw IOException("Selected publisher AES-128 key invalid")
        // Keep an additional copy of source data during ordered assembly.
        // The PC original was 1.416GB, so reserve at least 2GB on device.
        if(directory.usableSpace<2_000_000_000L)
            throw IOException("Insufficient Android storage for the selected full JK original")
        val durations=vod.lines().map{it.trim()}.filter{
            it.startsWith("#EXTINF:")
        }
        // Private durable fragments are held across interruption, unlike the
        // previous random /selected-jk-* temp directory deleted on ANY error.
        val fingerprint=stageFingerprint(
            article,media,frame,variant,plan,key,durations)
        val scratch=File(directory,"selected-jk-$fingerprint.stage")
        if(!scratch.isDirectory && !scratch.mkdirs())
            throw IOException("Cannot create selected JK durable staging")
        val cleaned=filenameStem.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""),"_")
            .take(140).ifBlank{"video"}
        val finalFile=File(directory,"$cleaned.ts")
        val tempFinal=File(scratch,"verified-original.ts")
        var completed=false
        val pool=Executors.newFixedThreadPool(4)
        try{
            val queue=ExecutorCompletionService<Pair<Int,Long>>(pool)
            var completedFragments=0
            var total=0L
            val missing=ArrayList<Pair<Int,String>>()
            for((index,url) in plan.segments.withIndex()){
                checkCancel()
                val fragment=File(scratch,"fragment-%04d.ts".format(Locale.ROOT,index))
                if(reusableFragment(fragment)){
                    total+=fragment.length()
                    completedFragments++
                }else {
                    // Never interpret a partial/stale fragment as verified
                    // progress; completed fragments have a SHA-256 sidecar.
                    missing+=index to url
                }
            }
            progress(fragmentPercent(completedFragments,plan.segments.size),total,
                completedFragments,plan.segments.size)
            for((index,url) in missing){
                queue.submit(Callable{
                    checkCancel()
                    val ciphertext=fetch(url,variant,MAX_FRAGMENT,proxy)
                    val clear=decodeSegment(ciphertext,key,segmentIv(plan,index))
                    val fragment=File(scratch,"fragment-%04d.ts".format(Locale.ROOT,index))
                    val digest=File(scratch,fragment.name+".sha256")
                    val pending=File(scratch,fragment.name+".pending")
                    val pendingHash=File(scratch,fragment.name+".sha256.pending")
                    try{
                        FileOutputStream(pending).use{
                            it.write(clear)
                            it.fd.sync()
                        }
                        pendingHash.writeText(hex(sha256(clear)))
                        checkCancel() // Retired attempts cannot publish stale fragments.
                        if(fragment.exists() && !fragment.delete())
                            throw IOException("Cannot replace corrupt JK fragment")
                        if(digest.exists() && !digest.delete())
                            throw IOException("Cannot replace corrupt JK fragment receipt")
                        if(!pending.renameTo(fragment) ||
                            !pendingHash.renameTo(digest))
                            throw IOException("Cannot persist selected JK fragment safely")
                        Pair(index,clear.size.toLong())
                    }finally{
                        pending.delete()
                        pendingHash.delete()
                    }
                })
            }
            repeat(missing.size){
                checkCancel()
                val (index,size)=queue.take().get()
                val fragment=File(scratch,"fragment-%04d.ts".format(Locale.ROOT,index))
                if(index !in plan.segments.indices || size<1504L ||
                    !reusableFragment(fragment))
                    throw IOException("Selected JK fragment checksum/ledger inconsistent")
                total+=size
                completedFragments++
                // A fragment is published only after its SHA sidecar was
                // verified. Emit each completed-fragment delta; the existing
                // cross-process UI observer coalesces bursts at 240ms. The
                // older every-eight-fragments gate concealed real transfers
                // (and speed) for long periods on slower connections.
                progress(fragmentPercent(completedFragments,plan.segments.size),total,
                    completedFragments,plan.segments.size)
            }
            checkCancel()
            if(total<30_000_000L)
                throw IOException("JK selected VOD failed whole-media size threshold")
            // Preserve every verified fragment if the full second copy
            // cannot fit. Never exhaust Android storage during assembly.
            val reserve=max(150_000_000L,total/10L)
            if(directory.usableSpace<total+reserve)
                throw IOException("Not enough storage to assemble selected JK "+
                    "original; verified fragments retained for resume")
            val digest=MessageDigest.getInstance("SHA-256")
            FileOutputStream(tempFinal).use{out->
                for(index in plan.segments.indices){
                    checkCancel()
                    val part=File(scratch,"fragment-%04d.ts".format(Locale.ROOT,index))
                    if(!part.isFile || part.length()<1504L)
                        throw IOException("JK final VOD missing a selected fragment")
                    part.inputStream().buffered().use{input->
                        val buffer=ByteArray(256*1024)
                        while(true){
                            val n=input.read(buffer)
                            if(n<0)break
                            out.write(buffer,0,n)
                            digest.update(buffer,0,n)
                        }
                    }
                    // Keep SHA-verified fragments through the ENTIRE
                    // ordered assembly, media validation and final rename.
                    // A failed playback check or pause must not destroy the
                    // first N fragments of the selected movie.
                    if(index%24==0)
                        progress(90+(index*9/plan.segments.size),total,
                            completedFragments,plan.segments.size)
                }
                out.fd.sync()
            }
            if(tempFinal.length()!=total)
                throw IOException("Selected JK whole-media byte ledger mismatched")
            val retriever=MediaMetadataRetriever()
            val actualMs=try {
                retriever.setDataSource(tempFinal.absolutePath)
                if(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)!="yes" ||
                    retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)!="yes")
                    throw IOException("Selected JK original is missing video/audio")
                retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?:throw IOException("Selected JK full media duration unavailable")
            }finally{retriever.release()}
            val expectedMs=plan.durationSeconds*1000.0
            if(abs(actualMs-expectedMs)>max(5000.0,expectedMs*0.01))
                throw IOException("Selected JK complete media disagrees with ENDLIST runtime")
            if(finalFile.exists()) {
                // A prior attempt may have completed the original but failed
                // subsequent MediaStore publication. Keep that validated file
                // only if it is byte-for-byte the SAME selected original.
                if(finalFile.length()!=total ||
                    fileHash(finalFile)!=hex(digest.digest()))
                    throw IOException("JK destination differs from selected original; refusing overwrite")
                if(!tempFinal.delete())
                    throw IOException("Cannot remove redundant verified JK assembly")
            }else if(!tempFinal.renameTo(finalFile))
                throw IOException("Cannot publish validated JK staging file")
            completed=true
            progress(99,total,completedFragments,plan.segments.size)
            return finalFile
        }finally{
            pool.shutdownNow()
            runCatching{pool.awaitTermination(8,TimeUnit.SECONDS)}
            if(completed) {
                // All fragment checksums were verified and one complete
                // original is now in the parent staging folder.
                scratch.listFiles()?.forEach{runCatching{it.delete()}}
                scratch.delete()
            }else {
                // Cancellation, a 403 or MediaMetadataRetriever failure must
                // NOT destroy previous complete selected fragments.
                runCatching{tempFinal.delete()}
            }
        }
    }
}
