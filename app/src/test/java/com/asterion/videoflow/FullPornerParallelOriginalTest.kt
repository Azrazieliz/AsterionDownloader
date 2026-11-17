package com.asterion.videoflow

import com.sun.net.httpserver.HttpServer
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

    private fun withServer(changedEtag:Boolean=false,
        block:(String,MutableList<String>)->Unit) {
        val received=CopyOnWriteArrayList<String>()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val executor=Executors.newFixedThreadPool(4)
        server.executor=executor
        server.createContext("/selected.mp4"){request->
            val range=request.requestHeaders.getFirst("Range").orEmpty()
            received.add(range)
            val parts=Regex("""bytes=(\d+)-(\d+)""").matchEntire(range)
            if(parts==null){
                request.sendResponseHeaders(400,-1)
                request.close()
            }else{
                val start=parts.groupValues[1].toInt()
                val end=parts.groupValues[2].toInt()
                if(start<0 || end>=bytes.size || end<start){
                    request.sendResponseHeaders(416,-1)
                    request.close()
                }else{
                    val invalid=changedEtag && start>=4*1024*1024-64
                    request.responseHeaders.add("Content-Type","video/mp4")
                    request.responseHeaders.add("ETag",if(invalid)"\"v2\"" else "\"v1\"")
                    request.responseHeaders.add("Content-Range",
                        "bytes "+start+"-"+end+"/"+bytes.size)
                    request.sendResponseHeaders(206,(end-start+1).toLong())
                    val body=request.responseBody
                    try{body.write(bytes,start,end-start+1)}finally{body.close()}
                }
            }
        }
        server.start()
        try{block("http://127.0.0.1:"+server.address.port+"/selected.mp4",received)}
        finally{server.stop(0);executor.shutdownNow()}
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
