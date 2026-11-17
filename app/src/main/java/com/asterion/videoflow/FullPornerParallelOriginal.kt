package com.asterion.videoflow

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Two-lane original media transfer. Eight lanes caused live HTTP 503;
 * two lanes transferred all 93,610,328 selected bytes in 227.6 seconds,
 * passed adjacent-range overlap checks and FFprobe on 2026-11-17.
 *
 * A sparse file never proves completion. Each finished 4MiB segment
 * receives a durable SHA256 ledger entry. Restarts rehash existing segments
 * and download only the missing ones. Never accept a different source/ETag.
 */
internal object FullPornerParallelOriginal {
    private const val CHUNK=4*1024*1024
    private const val MAX_SIZE=2_500_000_000L
    private data class Segment(val start:Long,val end:Long,
        val digest:String,val overlap:String)
    private fun sha(b:ByteArray):String=MessageDigest.getInstance("SHA-256")
        .digest(b).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun hex(b:ByteArray)=b.joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun range(raw:String):Triple<Long,Long,Long>? {
        val m=Regex("""bytes\s+(\d+)-(\d+)/(\d+)""").matchEntire(raw.trim())?:return null
        val a=m.groupValues.drop(1).map{it.toLongOrNull()?:return null}
        if(a[0]>a[1] || a[1]>=a[2] || a[2]<=0)return null
        return Triple(a[0],a[1],a[2])
    }
    private fun open(url:String,ref:String,from:Long,to:Long,
        proxy:Proxy?,localTest:Boolean):HttpURLConnection {
        var current=URI(url)
        repeat(6){hop->
            val local=localTest && current.host in setOf("127.0.0.1","localhost")
            require(current.userInfo==null && current.fragment==null &&
                (current.scheme=="https" || (local && current.scheme=="http"))) {
                "Untrusted original redirect"
            }
            val conn=(if(proxy==null)URL(current.toString()).openConnection()
                else URL(current.toString()).openConnection(proxy)) as HttpURLConnection
            conn.instanceFollowRedirects=false
            conn.connectTimeout=15_000
            conn.readTimeout=40_000
            conn.setRequestProperty("User-Agent",NativeMediaDownloader.USER_AGENT)
            conn.setRequestProperty("Referer",ref)
            conn.setRequestProperty("Accept-Encoding","identity")
            conn.setRequestProperty("Accept","video/mp4")
            conn.setRequestProperty("Range","bytes="+from+"-"+to)
            val status=try{conn.responseCode}catch(t:Throwable){
                conn.disconnect();throw t
            }
            if(status in 300..399){
                val next=conn.getHeaderField("Location")
                conn.disconnect()
                if(hop==5 || next.isNullOrBlank())throw IOException("Media redirect failed")
                current=current.resolve(next).normalize()
            }else return conn
        }
        throw IOException("Too many publisher media redirects")
    }
    private fun receive(conn:HttpURLConnection,start:Long,end:Long,
        total:Long?,etag:String?,cancelled:()->Boolean):Pair<ByteArray,Long> {
        if(conn.responseCode!=206)throw IOException(
            "Publisher selected original HTTP "+conn.responseCode+" (expected 206)")
        if(conn.contentType.orEmpty().substringBefore(';').trim()
            .lowercase()!="video/mp4")
            throw IOException("Selected source did not return video/mp4")
        val header=range(conn.getHeaderField("Content-Range").orEmpty())
            ?:throw IOException("Original missing Content-Range")
        if(header.first!=start || header.second!=end ||
            (total!=null && header.third!=total))
            throw IOException("Original source byte range changed")
        val receivedEtag=conn.getHeaderField("ETag").orEmpty().trim()
        if(!etag.isNullOrBlank() && receivedEtag.isNotBlank() &&
            etag!=receivedEtag)
            throw IOException("Original ETag changed")
        val count=(end-start+1).toInt()
        require(count in 1..CHUNK+64)
        val bytes=ByteArray(count)
        conn.inputStream.use{input->
            var offset=0
            while(offset<count){
                if(cancelled() || Thread.currentThread().isInterrupted)
                    throw CancellationException("Original transfer cancelled")
                val n=input.read(bytes,offset,count-offset)
                if(n<0)throw IOException("Original range truncated")
                if(n>0)offset+=n
            }
            if(input.read()!=-1)
                throw IOException("Original range contained extra bytes")
        }
        return bytes to header.third
    }
    private fun valid(stage:File,seg:Segment):Boolean=try{
        val size=(seg.end-seg.start+1).toInt()
        if(size !in 1..CHUNK || stage.length()<seg.end+1)false
        else {
            val bytes=ByteArray(size)
            RandomAccessFile(stage,"r").use{f->f.seek(seg.start);f.readFully(bytes)}
            sha(bytes)==seg.digest
        }
    }catch(_:Exception){false}
    private fun parse(s:String):Segment? {
        val p=s.split(',')
        if(p.size!=4)return null
        val a=p[0].toLongOrNull()?:return null
        val b=p[1].toLongOrNull()?:return null
        if(a<0 || b<a || !p[2].matches(Regex("[0-9a-f]{64}")) ||
            !p[3].matches(Regex("[0-9a-f]{0,128}")))return null
        return Segment(a,b,p[2],p[3])
    }
    private fun slices(total:Long):List<Pair<Long,Long>>{
        val result=ArrayList<Pair<Long,Long>>()
        var offset=0L
        while(offset<total) {
            val end=minOf(total-1,offset+CHUNK-1)
            result.add(offset to end)
            offset=end+1
        }
        return result
    }

    fun download(url:String,referer:String?,directory:File,filenameStem:String,
        proxy:Proxy?,progress:(Int)->Unit,cancelled:()->Boolean):File =
        run(url,referer.orEmpty(),directory,filenameStem,proxy,progress,cancelled,false)

    internal fun loopbackTest(url:String,referer:String,directory:File,
        cancelled:()->Boolean={false},progress:(Int)->Unit={}):File {
        require(URI(url).host in setOf("localhost","127.0.0.1"))
        return run(url,referer,directory,"unit",null,progress,cancelled,true)
    }

    private fun run(url:String,ref:String,directory:File,stemRaw:String,
        proxy:Proxy?,progress:(Int)->Unit,cancelled:()->Boolean,
        localTest:Boolean):File {
        if(!localTest)require(
            FullPornerOriginalSource.verifiedMediaHost(url) &&
            FullPornerOriginalSource.selectedPlayer(ref)) {
            "Not a publisher-attested FullPorner selected original"
        }
        require(directory.isDirectory && directory.canWrite())
        val stem=stemRaw.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""),"_")
            .trim().take(140).ifBlank{"video"}
        val result=File(directory,stem+".mp4")
        val receipt=File(directory,stem+".mp4.receipt")
        val finalOrigin=File(directory,"native_original.mp4.origin")
        val fingerprint=sha(url.toByteArray(Charsets.UTF_8))
        if(result.isFile && finalOrigin.isFile &&
            finalOrigin.readText().trim()==fingerprint &&
            NativeMediaReceipt.matches(result,receipt))return result
        val stage=File(directory,"native_fullporner_parallel.mp4.part")
        val ledger=File(directory,"native_fullporner_parallel.mp4.ledger")
        val origin=File(directory,"native_fullporner_parallel.mp4.origin")
        val info=File(directory,"native_fullporner_parallel.mp4.info")
        fun reset(){
            stage.delete();ledger.delete();origin.delete();info.delete()
        }
        val conn=open(url,ref,0,255,proxy,localTest)
        val total:Long
        val etag:String
        try{
            val proof=receive(conn,0,255,null,null,cancelled)
            total=proof.second
            etag=conn.getHeaderField("ETag").orEmpty().trim()
            if(proof.first.copyOfRange(4,8)
                .toString(Charsets.US_ASCII)!="ftyp")
                throw IOException("Selected source is not MP4")
        }finally{conn.disconnect()}
        if(total !in 1024..MAX_SIZE || directory.usableSpace<total+64L*1024*1024)
            throw IOException("Original too large or insufficient staging storage")
        val signature=fingerprint+"|"+total+"|"+etag
        if(runCatching{origin.readText().trim()}.getOrNull()!=fingerprint ||
            runCatching{info.readText().trim()}.getOrNull()!=signature)
            reset()
        if(!stage.isFile || stage.length()!=total) {
            reset()
            RandomAccessFile(stage,"rw").use{it.setLength(total)}
        }
        origin.writeText(fingerprint)
        info.writeText(signature)
        val ranges=slices(total)
        val accepted=LinkedHashMap<Long,Segment>()
        for(line in (if(ledger.isFile)ledger.readLines().take(ranges.size+2)
            else emptyList())){
            val seg=parse(line)?:continue
            if((seg.start to seg.end) in ranges && valid(stage,seg))
                accepted[seg.start]=seg
        }
        val lock=Any()
        val executor=Executors.newFixedThreadPool(2)
        try{
            val pending=ranges.filter{it.first !in accepted}
            val futures=pending.map{(start,end)->
                executor.submit(Callable<Segment> {
                    val rangeStart=maxOf(0,start-64)
                    var failure:IOException?=null
                    repeat(4){attempt->
                        if(cancelled() || Thread.currentThread().isInterrupted)
                            throw CancellationException("Original paused")
                        val response=try{
                            open(url,ref,rangeStart,end,proxy,localTest)
                        }catch(e:IOException){
                            failure=e
                            if(attempt<3)Thread.sleep(1500L*(attempt+1))
                            return@repeat
                        }
                        try{
                            val bytes=receive(response,rangeStart,end,total,
                                etag,cancelled).first
                            val overlap=(start-rangeStart).toInt()
                            val content=bytes.copyOfRange(overlap,bytes.size)
                            val segment=Segment(start,end,sha(content),
                                hex(bytes.copyOfRange(0,overlap)))
                            synchronized(lock) {
                                RandomAccessFile(stage,"rw").use{
                                    it.seek(start);it.write(content)
                                }
                                FileOutputStream(ledger,true).use{
                                    val line=segment.start.toString()+","+
                                        segment.end+","+segment.digest+","+
                                        segment.overlap+"\n"
                                    it.write(line.toByteArray(Charsets.UTF_8))
                                    it.fd.sync()
                                }
                            }
                            return@Callable segment
                        }catch(e:IOException){
                            failure=e
                            if(attempt<3)Thread.sleep(1500L*(attempt+1))
                        }finally{response.disconnect()}
                    }
                    throw failure?:IOException("Selected original range retry exhausted")
                })
            }
            var received=accepted.values.sumOf{it.end-it.start+1}
            progress(((received*95)/total).toInt().coerceIn(0,95))
            for(future in futures){
                val seg=try{future.get()}catch(e:java.util.concurrent.ExecutionException){
                    throw e.cause?:e
                }
                accepted[seg.start]=seg
                received+=seg.end-seg.start+1
                progress(((received*95)/total).toInt().coerceIn(0,95))
                if(cancelled())throw CancellationException("Original paused")
            }
            if(received!=total || accepted.size!=ranges.size)
                throw IOException("Selected original missing segments")
            RandomAccessFile(stage,"r").use{f->
                for((start,end) in ranges){
                    val seg=accepted[start]?:throw IOException("Missing original ledger")
                    if(seg.end!=end || !valid(stage,seg))
                        throw IOException("Original segment digest mismatch")
                    if(seg.overlap.isNotBlank()){
                        val n=seg.overlap.length/2
                        val previous=ByteArray(n)
                        f.seek(start-n)
                        f.readFully(previous)
                        if(hex(previous)!=seg.overlap)
                            throw IOException("Selected original overlap changed")
                    }
                }
            }
            if(result.isFile && !result.delete())
                throw IOException("Cannot replace prior incomplete original")
            if(!stage.renameTo(result))
                throw IOException("Cannot publish verified selected original")
            NativeMediaReceipt.write(result,receipt)
            finalOrigin.writeText(fingerprint)
            ledger.delete();origin.delete();info.delete()
            return result
        }finally{
            executor.shutdownNow()
            executor.awaitTermination(2,TimeUnit.SECONDS)
        }
    }
}
