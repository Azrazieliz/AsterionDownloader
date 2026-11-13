package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.concurrent.thread

/** Real HTTP fetch + chapter extraction + EPUB ZIP, against provider-shaped
 * fixture responses. No webpage strings are counted as a completed book.
 */
class ActualEpubDownloadSimulationTest {
    private class ChapterFixture(private val badChapter:Int=-1):Closeable {
        private val socket=ServerSocket(0,16,InetAddress.getByName("127.0.0.1"))
        @Volatile private var closed=false
        private val server=thread(name="epub-http-provider",isDaemon=true){
            while(!closed){
                val client=try{socket.accept()}catch(_:SocketException){break}
                thread(isDaemon=true){
                    client.use{conn->
                        val reader=conn.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val path=reader.readLine()?.split(' ')?.getOrNull(1).orEmpty()
                        while(true){if(reader.readLine().isNullOrEmpty())break}
                        val chapter=path.substringAfterLast('/').toIntOrNull()?:0
                        val paragraph=if(chapter==badChapter)"No text" else
                            (1..6).joinToString(" "){
                                "Complete chapter "+chapter+" original paragraph "+it+
                                    " has a real long-form sentence with narrative content."
                            }
                        val content=when(chapter%3){
                            0->"<div id='article'><h4>Chapter "+chapter+
                                ": First Reader</h4><p>"+paragraph+"</p></div>"
                            1->"<div class='chapter-content'><h2>Chapter "+chapter+
                                ": Second Reader</h2><p>"+paragraph+"</p></div>"
                            else->"<div id='chapter-content'><h3>Chapter "+chapter+
                                ": Third Reader</h3><p>"+paragraph+"</p></div>"
                        }
                        val html=("<html><head><title>Chapter "+chapter+
                            "</title></head><body>"+content+"</body></html>")
                            .toByteArray(Charsets.UTF_8)
                        val out=conn.getOutputStream()
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n"+
                            "Content-Length: "+html.size+"\r\nConnection: close\r\n\r\n")
                            .toByteArray(Charsets.US_ASCII))
                        out.write(html);out.flush()
                    }
                }
            }
        }
        fun url(n:Int)="http://127.0.0.1:"+socket.localPort+"/chapter/"+n
        override fun close(){closed=true;socket.close();server.join(3_000)}
    }
    private fun fetchChapter(url:String):NovelText {
        val page=Jsoup.connect(url).timeout(5_000).get()
        val metadata=NovelEpubParser.readerChapter(page,url)
        val text=NovelEpubParser.chapterText(page)
        assertTrue("provider number must be exact",metadata.number>=1.0)
        return text
    }

    @Test fun fourteenActualDownloadedChaptersBecomeCompleteOfflineEpub(){
        ChapterFixture().use{site->
            val file=File.createTempFile("asterion-complete-book",".epub")
            try{
                val titles=(1..14).map{"Chapter "+it}
                file.outputStream().use{out->
                    NovelEpubParser.writeEpubStreaming(out,"Real Test Book",
                        "https://freewebnovel.com/novel/test-book",
                        titles){index->fetchChapter(site.url(index+1))}
                }
                ZipFile(file).use{zip->
                    val mimetype=zip.getEntry("mimetype")
                    assertNotNull(mimetype)
                    assertEquals(0,mimetype.method)
                    assertEquals("application/epub+zip",zip.getInputStream(mimetype)
                        .bufferedReader().readText())
                    assertNotNull(zip.getEntry("META-INF/container.xml"))
                    assertNotNull(zip.getEntry("OEBPS/content.opf"))
                    assertNotNull(zip.getEntry("OEBPS/nav.xhtml"))
                    for(n in 1..14){
                        val entry=zip.getEntry("OEBPS/chapter-"+n+".xhtml")
                        assertNotNull("missing chapter "+n,entry)
                        val text=zip.getInputStream(entry).bufferedReader().readText()
                        assertTrue("wrong chapter text "+n,
                            text.contains("Complete chapter "+n+" original paragraph"))
                    }
                }
                assertTrue(file.length()>2_000)
            }finally{file.delete()}
        }
    }

    @Test fun chapterThirteenShortHtmlStopsEpubInsteadOfFabricatingCompletion(){
        ChapterFixture(badChapter=13).use{site->
            val file=File.createTempFile("asterion-incomplete-book",".part")
            try{
                val failure=runCatching{
                    file.outputStream().use{out->
                        NovelEpubParser.writeEpubStreaming(out,"Incomplete Test",
                            "https://novelbuddy.me/novel/test-book",
                            (1..14).map{"Chapter "+it}){n->
                                fetchChapter(site.url(n+1))
                            }
                    }
                }.exceptionOrNull()
                assertNotNull("incomplete chapter must fail",failure)
                assertTrue(failure!!.message.orEmpty().contains("missing or truncated"))
            }finally{file.delete()}
        }
    }
}
