package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Real HttpURLConnection transfers through a disposable loopback HTTP server. */
class ActualDownloadSimulationTest {
    private data class Request(val path:String,val headers:Map<String,String>){
        fun header(name:String)=headers[name.lowercase(Locale.ROOT)].orEmpty()
    }
    private class Fixture(private val response:(Request,OutputStream)->Unit):Closeable {
        private val server=ServerSocket(0,16,InetAddress.getByName("127.0.0.1"))
        private val stopped=AtomicBoolean(false)
        val requests=CopyOnWriteArrayList<Request>()
        private val accepting=thread(name="asterion-loopback-http",isDaemon=true){
            while(!stopped.get()){
                val socket=try{server.accept()}catch(_:SocketException){break}
                thread(isDaemon=true){
                    socket.use{client->
                        client.soTimeout=8_000
                        val reader=client.getInputStream()
                            .bufferedReader(Charsets.ISO_8859_1)
                        val path=reader.readLine()?.split(' ')?.getOrNull(1)
                            ?:return@use
                        val headers=linkedMapOf<String,String>()
                        while(true){
                            val line=reader.readLine()?:break
                            if(line.isBlank())break
                            val index=line.indexOf(':')
                            if(index>0)headers[line.substring(0,index).trim()
                                .lowercase(Locale.ROOT)]=line.substring(index+1).trim()
                        }
                        val request=Request(path,headers)
                        requests+=request
                        runCatching{response(request,client.getOutputStream())}
                    }
                }
            }
        }
        fun url(path:String)= "http://127.0.0.1:"+server.localPort+path
        fun alias(path:String)="http://localhost:"+server.localPort+path
        override fun close(){
            stopped.set(true);server.close();accepting.join(3_000)
        }
    }
    private fun write(out:OutputStream,status:String,body:ByteArray,
                      mime:String="video/mp4",headers:Map<String,String> = emptyMap(),
                      length:Long=body.size.toLong()){
        val head=StringBuilder("HTTP/1.1 "+status+"\r\n")
            .append("Content-Type: "+mime+"\r\n")
            .append("Content-Length: "+length+"\r\n")
        for((k,v) in headers)head.append(k).append(": ").append(v).append("\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.US_ASCII))
        out.write(body);out.flush()
    }
    private fun directory()=Files.createTempDirectory("asterion-actual-http").toFile()

    @Test fun brokenConnectionKeepsPartialAndResumesRealOriginalBytes(){
        val payload=ByteArray(470_021){(it*17%251).toByte()}
        val calls=AtomicInteger()
        Fixture{req,out->
            if(calls.incrementAndGet()==1){
                write(out,"200 OK",payload.copyOfRange(0,55_131),
                    headers=mapOf("ETag" to "\"stable-original\""),
                    length=payload.size.toLong())
            }else{
                val offset=req.header("Range").removePrefix("bytes=")
                    .substringBefore('-').toInt()
                write(out,"206 Partial Content",payload.copyOfRange(offset,payload.size),
                    headers=mapOf("ETag" to "\"stable-original\"",
                        "Content-Range" to ("bytes "+offset+"-"+(payload.size-1)+"/"+payload.size)))
            }
        }.use{http->
            val dir=directory()
            try{
                assertNotNull(runCatching{
                    NativeMediaDownloader.download(http.url("/movie.mp4"),null,dir)
                }.exceptionOrNull())
                val part=File(dir,"native_original.mp4.part")
                assertTrue(part.isFile)
                assertEquals(55_131L,part.length())
                assertTrue(File(dir,"native_original.mp4.etag").isFile)
                val saved=NativeMediaDownloader.download(http.url("/movie.mp4"),null,dir)
                assertArrayEquals(payload,saved.readBytes())
                assertFalse(part.exists())
                assertEquals(2,calls.get())
                assertEquals("bytes=55131-",http.requests[1].header("Range"))
                assertEquals("\"stable-original\"",http.requests[1].header("If-Range"))
            }finally{dir.deleteRecursively()}
        }
    }

    @Test fun cancellationPreservesPartButNeverPublishesIncompleteVideo(){
        val payload=ByteArray(640_000){(it%235).toByte()}
        Fixture{_,out->write(out,"200 OK",payload)}.use{http->
            val dir=directory();val cancelled=AtomicBoolean(false)
            try{
                val problem=runCatching{
                    NativeMediaDownloader.download(http.url("/scene.mp4"),null,dir,
                        progress={if(it>0)cancelled.set(true)},
                        cancelled={cancelled.get()})
                }.exceptionOrNull()
                assertTrue(problem is java.util.concurrent.CancellationException)
                assertTrue(File(dir,"native_original.mp4.part").exists())
                assertFalse(File(dir,"video.mp4").exists())
            }finally{dir.deleteRecursively()}
        }
    }

    @Test fun accessDeniedRateLimitAndHtmlCannotBeSavedAsVideo(){
        val errors=listOf(
            Triple("403 Forbidden","text/html","Forbidden"),
            Triple("429 Too Many Requests","text/html","Slow down"),
            Triple("200 OK","text/html","<h1>Verify you are human</h1>")
        )
        for((status,mime,body) in errors){
            Fixture{_,out->write(out,status,body.toByteArray(),mime)}.use{http->
                val dir=directory()
                try{
                    assertNotNull(runCatching{
                        NativeMediaDownloader.download(http.url("/video.mp4"),null,dir)
                    }.exceptionOrNull())
                    assertFalse(File(dir,"video.mp4").exists())
                    assertFalse(File(dir,"native_original.mp4.part").exists())
                }finally{dir.deleteRecursively()}
            }
        }
    }

    @Test fun crossHostRedirectNeverLeaksAuthenticatedCookies(){
        val payload=ByteArray(9_071){(it%199).toByte()}
        lateinit var fixture:Fixture
        fixture=Fixture{req,out->
            if(req.path=="/start.mp4")
                write(out,"302 Found",byteArrayOf(),headers=mapOf(
                    "Location" to fixture.alias("/final.mp4")))
            else write(out,"200 OK",payload)
        }
        fixture.use{http->
            val dir=directory()
            try{
                val saved=NativeMediaDownloader.download(http.url("/start.mp4"),null,dir,
                    cookieHeader="session_private=must-not-leak")
                assertArrayEquals(payload,saved.readBytes())
                assertEquals(2,http.requests.size)
                assertEquals("session_private=must-not-leak",http.requests[0].header("Cookie"))
                assertTrue(http.requests[1].header("Cookie").isBlank())
            }finally{dir.deleteRecursively()}
        }
    }

    @Test fun twoConcurrentActualDownloadsKeepDistinctFiles(){
        val first=ByteArray(163_000){(it%241).toByte()}
        val second=ByteArray(182_000){(it%243).toByte()}
        Fixture{req,out->write(out,"200 OK",if(req.path.startsWith("/a"))first else second)}
            .use{http->
                val dir1=directory();val dir2=directory()
                try{
                    var a:File?=null;var b:File?=null
                    val t1=thread{a=NativeMediaDownloader.download(http.url("/a.mp4"),null,dir1)}
                    val t2=thread{b=NativeMediaDownloader.download(http.url("/b.mp4"),null,dir2)}
                    t1.join(20_000);t2.join(20_000)
                    assertFalse("first transfer stalled",t1.isAlive)
                    assertFalse("second transfer stalled",t2.isAlive)
                    assertArrayEquals(first,a!!.readBytes())
                    assertArrayEquals(second,b!!.readBytes())
                }finally{dir1.deleteRecursively();dir2.deleteRecursively()}
            }
    }
}
