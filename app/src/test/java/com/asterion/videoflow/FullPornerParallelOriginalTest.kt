package com.asterion.videoflow

import java.io.Closeable
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.Locale
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Actual localhost HTTP 206 bytes, two-connection byte-range transfer,
 * source SHA ledger, restart recovery and final non-sparse SHA receipt.
 */
class FullPornerParallelOriginalTest {
    private val bytes=ByteArray(4*1024*1024+2807){(it*17%251).toByte()}
        .also {
            "ftyp".toByteArray(Charsets.US_ASCII).copyInto(it,4)
        }

    private data class Request(val headers:Map<String,String>) {
        fun header(name:String)=headers[name.lowercase(Locale.ROOT)].orEmpty()
    }
    /** Same real localhost TCP fixture already used by
     * ActualDownloadSimulationTest; com.sun.net.httpserver is not exposed
     * by Android's JVM-unit-test compile SDK. */
    private class Fixture(private val respond:(Request,OutputStream)->Unit):Closeable {
        private val server=ServerSocket(0,16,InetAddress.getByName("127.0.0.1"))
        private val stopped=AtomicBoolean(false)
        val requests=CopyOnWriteArrayList<String>()
        private val accepting=thread(name="asterion-fp-http",isDaemon=true) {
            while(!stopped.get()){
                val socket=try{server.accept()}catch(_:SocketException){break}
                thread(isDaemon=true){
                    socket.use {client->
                        client.soTimeout=15_000
                        val reader=client.getInputStream()
                            .bufferedReader(Charsets.ISO_8859_1)
                        if(reader.readLine()==null)return@use
                        val headers=linkedMapOf<String,String>()
                        while(true){
                            val line=reader.readLine()?:break
                            if(line.isBlank())break
                            val p=line.indexOf(':')
                            if(p>0)headers[line.substring(0,p).trim()
                                .lowercase(Locale.ROOT)]=line.substring(p+1).trim()
                        }
                        val req=Request(headers)
                        requests.add(req.header("Range"))
                        runCatching{respond(req,client.getOutputStream())}
                    }
                }
            }
        }
        fun url()="http://127.0.0.1:"+server.localPort+"/selected.mp4"
        override fun close(){stopped.set(true);server.close();accepting.join(3000)}
    }
    private fun response(out:OutputStream,status:String,from:Int,to:Int,
        changedEtag:Boolean) {
        val head="HTTP/1.1 "+status+"\r\n"+
            "Content-Type: video/mp4\r\n"+
            "Content-Length: "+(to-from+1)+"\r\n"+
            "Content-Range: bytes "+from+"-"+to+"/"+bytes.size+"\r\n"+
            "ETag: "+(if(changedEtag)"\"v2\"" else "\"v1\"")+"\r\n"+
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes,from,to-from+1)
        out.flush()
    }
    private fun withServer(changedEtag:Boolean=false,
        block:(String,MutableList<String>)->Unit) {
        Fixture{req,out->
            val parts=Regex("""bytes=(\d+)-(\d+)""")
                .matchEntire(req.header("Range"))
            if(parts==null){
                out.write(("HTTP/1.1 400 Bad Request\r\n"+
                    "Content-Length: 0\r\nConnection: close\r\n\r\n")
                    .toByteArray(Charsets.US_ASCII))
                out.flush()
            }else{
                val from=parts.groupValues[1].toInt()
                val to=parts.groupValues[2].toInt()
                if(from<0 || to<from || to>=bytes.size){
                    out.write(("HTTP/1.1 416 Range Not Satisfiable\r\n"+
                        "Content-Length: 0\r\nConnection: close\r\n\r\n")
                        .toByteArray(Charsets.US_ASCII))
                    out.flush()
                }else response(out,"206 Partial Content",from,to,
                    changedEtag && from>=4*1024*1024-64)
            }
        }.use{http->block(http.url(),http.requests)}
    }

    @Test fun twoVerifiedRangesMakeExactPlayableBytesAndReceipt(){
        withServer {link,requests->
            val dir=Files.createTempDirectory("asterion_fp_test_").toFile()
            try{
                val file=FullPornerParallelOriginal.loopbackTest(
                    link,"https://fullporner.com/player/3105043/2",dir)
                assertEquals(bytes.size.toLong(),file.length())
                assertArrayEquals(bytes,file.readBytes())
                assertTrue(NativeMediaReceipt.matches(file,
                    java.io.File(dir,"unit.mp4.receipt")))
                assertEquals(3,requests.size) // probe + 2 full ranges
                assertEquals("bytes=0-255",requests[0])
                assertTrue(requests.contains("bytes=0-4194303"))
                assertTrue(requests.contains(
                    "bytes=4194240-"+(bytes.size-1)))
                // Reopening the same selected original uses verified source
                // binding and complete SHA; it must not download again.
                val same=FullPornerParallelOriginal.loopbackTest(
                    link,"https://fullporner.com/player/3105043/2",dir)
                assertEquals(file.canonicalPath,same.canonicalPath)
                assertEquals(3,requests.size)
            }finally{dir.deleteRecursively()}
        }
    }
    @Test fun interruptedDownloadKeepsHashedLedgerAndResumesSafely(){
        withServer {link,_->
            val dir=Files.createTempDirectory("asterion_fp_resume_").toFile()
            val stopped=AtomicBoolean(false)
            try{
                val original=runCatching {
                    // The first completed range is accepted and written to a
                    // durable SHA ledger before progress requests a pause.
                    FullPornerParallelOriginal.loopbackTest(
                        link,"https://fullporner.com/player/3105043/2",
                        dir,cancelled={stopped.get()},
                        progress={ if(it>1)stopped.set(true) })
                }
                assertTrue(original.isFailure)
                assertTrue(java.io.File(dir,
                    "native_fullporner_parallel.mp4.ledger").isFile)
                val result=FullPornerParallelOriginal.loopbackTest(
                    link,"https://fullporner.com/player/3105043/2",dir)
                assertArrayEquals(bytes,result.readBytes())
                assertTrue(NativeMediaReceipt.matches(result,
                    java.io.File(dir,"unit.mp4.receipt")))
            }finally{dir.deleteRecursively()}
        }
    }
    @Test fun mismatchedSegmentETagNeverPublishesCompleteOriginal(){
        withServer(changedEtag=true) {link,_->
            val dir=Files.createTempDirectory("asterion_fp_etag_").toFile()
            try{
                val failed=runCatching {
                    FullPornerParallelOriginal.loopbackTest(
                        link,"https://fullporner.com/player/3105043/2",dir)
                }
                assertTrue("Changed ETag must fail",failed.isFailure)
                assertFalse(java.io.File(dir,"unit.mp4").exists())
                assertFalse(java.io.File(dir,"unit.mp4.receipt").exists())
            }finally{dir.deleteRecursively()}
        }
    }
}
