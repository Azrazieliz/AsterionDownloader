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

    fun eligible(article:String,source:String,referer:String?):Boolean {
        val p=parsed(article)?:return false
        val h=parsed(source)?:return false
        val frame=referer?.let(::parsed)?:return false
        return p.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")=="jav.guru" &&
            selectedArticle.matches(p.path.orEmpty()) &&
            h.host.equals(CDN,ignoreCase=true) &&
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
        val keyRaw=Regex("""(?:^|,)URI="([^"]{1,2200})"""")
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
                 progress:(Int)->Unit={},cancelled:()->Boolean={false}):File {
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
        val scratch=File.createTempFile("selected-jk-", ".stage",directory)
        if(!scratch.delete() || !scratch.mkdir())
            throw IOException("Cannot create isolated JK media staging")
        val cleaned=filenameStem.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""),"_")
            .take(140).ifBlank{"video"}
        val finalFile=File(directory,"$cleaned.ts")
        val tempFinal=File(scratch,"verified-original.ts")
        var completed=false
        val pool=Executors.newFixedThreadPool(4)
        try{
            val queue=ExecutorCompletionService<Pair<Int,Long>>(pool)
            for((index,url) in plan.segments.withIndex()){
                queue.submit(Callable{
                    checkCancel()
                    val ciphertext=fetch(url,variant,MAX_FRAGMENT,proxy)
                    val clear=decodeSegment(ciphertext,key,segmentIv(plan,index))
                    val fragment=File(scratch,"fragment-%04d.ts".format(Locale.ROOT,index))
                    FileOutputStream(fragment).use{it.write(clear)}
                    Pair(index,clear.size.toLong())
                })
            }
            var total=0L
            repeat(plan.segments.size){completedFragments->
                checkCancel()
                val (index,size)=queue.take().get()
                if(index !in plan.segments.indices || size<1504L)
                    throw IOException("Selected JK segment ledger inconsistent")
                total+=size
                if(completedFragments%8==0 || completedFragments==plan.segments.size-1)
                    progress(5+((completedFragments+1)*78/plan.segments.size))
            }
            checkCancel()
            if(total<30_000_000L)
                throw IOException("JK selected VOD failed whole-media size threshold")
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
                    if(!part.delete())
                        throw IOException("Unable to release selected fragment staging")
                    if(index%24==0)progress(84+(index*12/plan.segments.size))
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
            if(finalFile.exists())
                throw IOException("Selected JK destination already exists; refusing overwrite")
            if(!tempFinal.renameTo(finalFile))
                throw IOException("Cannot publish validated JK staging file")
            completed=true
            progress(100)
            return finalFile
        }finally{
            pool.shutdownNow()
            runCatching{pool.awaitTermination(8,TimeUnit.SECONDS)}
            scratch.listFiles()?.forEach{runCatching{it.delete()}}
            scratch.delete()
            if(!completed)runCatching{tempFinal.delete()}
        }
    }
}
