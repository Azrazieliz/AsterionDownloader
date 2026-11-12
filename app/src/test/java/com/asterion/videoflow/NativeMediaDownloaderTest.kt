package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class NativeMediaDownloaderTest {
    /** Plain JVM socket fixture: does not require the jdk.httpserver module
     * which is unavailable to Android Gradle's test compilation classpath.
     */
    private fun serve(mime:String,payload:ByteArray,use:(Int)->Unit) {
        ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use{server->
            val peer=thread(name="native-media-fixture"){
                runCatching{
                    server.accept().use{socket->
                        socket.soTimeout=3000
                        val input=socket.getInputStream()
                        var previous=0
                        var count=0
                        // Read the request headers without consuming the body.
                        while(count++<8192){
                            val current=input.read()
                            if(current<0)break
                            if(previous==13 && current==10){
                                // The next empty line terminates request headers.
                                if(input.available()==0)break
                            }
                            previous=current
                        }
                        val response="HTTP/1.1 200 OK\r\n"+
                            "Content-Type: $mime\r\n"+
                            "Content-Length: ${payload.size}\r\n"+
                            "ETag: \"stable-test\"\r\n"+
                            "Connection: close\r\n\r\n"
                        socket.getOutputStream().use{output->
                            output.write(response.toByteArray(Charsets.UTF_8))
                            output.write(payload)
                            output.flush()
                        }
                    }
                }
            }
            try{use(server.localPort)}
            finally{server.close();peer.join(3_000)}
        }
    }
    @Test fun onlyConcreteMediaUrlsUseNativeStreamingLane(){
        assertTrue(NativeMediaDownloader.isDirectMediaUrl(
            "https://cdn.example.org/original.mp4?token=abc"))
        assertTrue(NativeMediaDownloader.isDirectMediaUrl(
            "https://www.freepornvideos.xxx/get_file/signed/original.mp4/?token=abc"))
        assertTrue(NativeMediaDownloader.isDirectMediaUrl(
            "https://cdn.example.org/scene.mkv"))
        assertFalse(NativeMediaDownloader.isDirectMediaUrl(
            "https://provider.example/watch/scene"))
        assertFalse(NativeMediaDownloader.isDirectMediaUrl(
            "https://provider.example/stream.m3u8"))
        assertFalse(NativeMediaDownloader.isDirectMediaUrl(
            "https://evil.example/scene.mp4.html"))
        assertTrue(NativeMediaDownloader.isNativeEligible(
            "https://mp4hentai.com/2026/11/anime/",
            "https://cdn.example/scene.mp4"))
        assertTrue(NativeMediaDownloader.isNativeEligible(
            "https://hentaibros.net/example/",
            "https://cdn.example/scene.mp4"))
        assertTrue(NativeMediaDownloader.isNativeEligible(
            "https://watchporn.to/video/scene/",
            "https://cdn.example/scene.mp4"))
    }
    @Test fun nativeTransferWritesRealOriginalBytes(){
        val payload=ByteArray(160_000){(it%239).toByte()}
        serve("video/mp4",payload){port->
            val dir=java.nio.file.Files.createTempDirectory("native-video-").toFile()
            try{
                val file=NativeMediaDownloader.download(
                    "http://127.0.0.1:$port/media.mp4",null,dir)
                assertTrue(file.isFile)
                assertArrayEquals(payload,file.readBytes())
                // Signed original links may use a slash after the extension.
                assertTrue(NativeMediaDownloader.isDirectMediaUrl(
                    "http://127.0.0.1:$port/media.mp4/?auth=token"))
                assertTrue(file.name.endsWith(".mp4"))
                assertFalse(File(dir,"native_original.mp4.part").exists())
            }finally{dir.deleteRecursively()}
        }
    }
    @Test fun nativeTransferRejectsHtmlMasqueradingAsVideo(){
        serve("text/html","<html>blocked</html>".toByteArray()){port->
            val dir=java.nio.file.Files.createTempDirectory("native-video-").toFile()
            try{
                runCatching{NativeMediaDownloader.download(
                    "http://127.0.0.1:$port/media.mp4",null,dir)}
                    .onSuccess{fail("HTML is not an original media file")}
                    .onFailure{assertTrue(
                        it.message.orEmpty().contains("instead of a video"))}
            }finally{dir.deleteRecursively()}
        }
    }
}
