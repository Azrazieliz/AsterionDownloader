package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

class NativeMediaDownloaderTest {
    @Test fun onlyConcreteMediaUrlsUseNativeStreamingLane(){
        assertTrue(NativeMediaDownloader.isDirectMediaUrl(
            "https://cdn.example.org/original.mp4?token=abc"))
        assertTrue(NativeMediaDownloader.isDirectMediaUrl(
            "https://cdn.example.org/scene.mkv"))
        assertFalse(NativeMediaDownloader.isDirectMediaUrl(
            "https://provider.example/watch/scene"))
        assertFalse(NativeMediaDownloader.isDirectMediaUrl(
            "https://provider.example/stream.m3u8"))
        assertFalse(NativeMediaDownloader.isDirectMediaUrl(
            "https://evil.example/scene.mp4.html"))
    }
    @Test fun nativeTransferWritesRealOriginalBytes(){
        val payload=ByteArray(160_000){(it%239).toByte()}
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/media.mp4"){exchange->
            exchange.responseHeaders.add("Content-Type","video/mp4")
            exchange.responseHeaders.add("ETag","\"stable-1\"")
            exchange.sendResponseHeaders(200,payload.size.toLong())
            exchange.responseBody.use{it.write(payload)}
        }
        server.start()
        val dir=java.nio.file.Files.createTempDirectory("native-video-").toFile()
        try{
            val url="http://127.0.0.1:${server.address.port}/media.mp4"
            val file=NativeMediaDownloader.download(url,null,dir)
            assertTrue(file.isFile)
            assertArrayEquals(payload,file.readBytes())
            assertTrue(file.name.endsWith(".mp4"))
            assertFalse(File(dir,"native_original.mp4.part").exists())
        }finally{
            server.stop(0)
            dir.deleteRecursively()
        }
    }
    @Test fun nativeTransferRejectsHtmlMasqueradingAsVideo(){
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/media.mp4"){exchange->
            exchange.responseHeaders.add("Content-Type","text/html")
            val payload="<html>blocked</html>".toByteArray()
            exchange.sendResponseHeaders(200,payload.size.toLong())
            exchange.responseBody.use{it.write(payload)}
        }
        server.start()
        val dir=java.nio.file.Files.createTempDirectory("native-video-").toFile()
        try{
            runCatching{NativeMediaDownloader.download(
                "http://127.0.0.1:${server.address.port}/media.mp4",null,dir)}
                .onSuccess{fail("HTML is not an original media file")}
                .onFailure{assertTrue(it.message.orEmpty().contains("instead of a video"))}
        }finally{server.stop(0);dir.deleteRecursively()}
    }
}
