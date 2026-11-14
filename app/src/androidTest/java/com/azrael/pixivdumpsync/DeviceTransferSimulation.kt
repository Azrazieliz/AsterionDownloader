package com.azrael.pixivdumpsync

import android.app.Instrumentation
import android.os.Bundle
import android.util.Log
import com.asterion.videoflow.NativeMediaDownloader
import com.asterion.videoflow.EmbeddedYtDlpMetadata
import com.asterion.videoflow.EmbeddedYtDlpTransfer
import com.asterion.videoflow.LegacyYtDlpTransfer
import com.asterion.videoflow.VideoExtractorVersion
import com.chaquo.python.Python
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * Real phonepilot Android emulator test (not a mocked transfer).
 * Runs a controlled first-party HTTP server inside the emulator and executes
 * the same native downloader and embedded Chaquopy yt-dlp paths as VideoFlow.
 * Never contacts external adult providers, user accounts or unrelated media.
 */
class DeviceTransferSimulation:Instrumentation() {
    // Instrumentation.onCreate is a no-op: explicitly start the runner thread.
    // Otherwise onStart is never invoked, `am instrument -w` hangs until
    // the CI timeout, and no native/Python transfer is actually tested.
    override fun onCreate(arguments:Bundle?) {
        super.onCreate(arguments)
        start()
    }
    private fun status(message:String){
        Log.i("ASTERION_SIM","AST_SIM: "+message)
        sendStatus(0,Bundle().apply{
            putString(Instrumentation.REPORT_KEY_STREAMRESULT,
                "AST_SIM: "+message+"\n")
        })
    }
    override fun onStart(){
        Log.i("ASTERION_SIM","AST_SIM: INSTRUMENTATION_ON_START")
        super.onStart()
        thread(name="android-real-downloader-simulation") {
            Log.i("ASTERION_SIM","AST_SIM: WORKER_STARTED")
            val context=targetContext
            val root=File(context.cacheDir,"actual-download-simulation")
            root.mkdirs()
            val payload=ByteArray(256_013){(it*13%251).toByte()}
            val server=ServerSocket(0,16,InetAddress.getByName("127.0.0.1"))
            val ended=java.util.concurrent.atomic.AtomicBoolean(false)
            val serving=thread(name="local-media-server",isDaemon=true){
                while(!ended.get()){
                    val peer=try{server.accept()}catch(_:SocketException){break}
                    thread(isDaemon=true){
                        peer.use{socket->
                            socket.soTimeout=15_000
                            val reader=socket.getInputStream()
                                .bufferedReader(Charsets.ISO_8859_1)
                            val request=reader.readLine().orEmpty()
                            var rangeHeader=""
                            while(true){
                                val line=reader.readLine().orEmpty()
                                if(line.isEmpty())break
                                if(line.startsWith("Range:",true))rangeHeader=line
                            }
                            val chunked=request.contains("/chunked-original.mp4")
                            val offset=if(chunked)Regex("""bytes=([0-9]+)-""")
                                .find(rangeHeader)?.groupValues?.get(1)?.toIntOrNull()?:0 else 0
                            check(offset in 0 until payload.size)
                            val end=if(chunked)minOf(offset+16_383,payload.size-1)
                                else payload.size-1
                            val length=end-offset+1
                            val header=(if(chunked){
                                "HTTP/1.1 206 Partial Content\r\n"+
                                "Content-Range: bytes "+offset+"-"+end+"/"+payload.size+"\r\n"
                            }else "HTTP/1.1 200 OK\r\n")+
                                "Content-Type: video/mp4\r\nContent-Length: "+
                                length+"\r\nETag: \"local-static-test\"\r\n"+
                                "Connection: close\r\n\r\n"
                            val out=socket.getOutputStream()
                            out.write(header.toByteArray(Charsets.US_ASCII))
                            if(!request.startsWith("HEAD "))out.write(payload,offset,length)
                            out.flush()
                        }
                    }
                }
            }
            val url="http://127.0.0.1:"+server.localPort+"/genuine-original.mp4"
            try {
                status("BEGIN "+context.packageName)
                Log.i("ASTERION_SIM","AST_SIM: SERVER_LISTENING "+server.localPort)
                val native=File(root,"native").apply{mkdirs()}
                Log.i("ASTERION_SIM","AST_SIM: NATIVE_BEGIN")
                val completed=NativeMediaDownloader.download(url,null,native)
                check(completed.readBytes().contentEquals(payload)){
                    "Native Android transfer produced corrupted bytes"
                }
                status("NATIVE_OK "+completed.length()+" bytes / exact SHA-equivalent")
                val ranged=File(root,"chunked").apply{mkdirs()}
                val rangeResult=NativeMediaDownloader.download(
                    "http://127.0.0.1:"+server.localPort+"/chunked-original.mp4",
                    null,ranged)
                check(rangeResult.readBytes().contentEquals(payload)){
                    "Contiguous 206 chunks failed to reconstruct complete original"
                }
                status("NATIVE_206_OK "+rangeResult.length()+" bytes / exact SHA-equivalent")
                val embedded=File(root,"chaquopy").apply{mkdirs()}
                Log.i("ASTERION_SIM","AST_SIM: PYTHON_BEGIN")
                LegacyYtDlpTransfer.initialize(context)
                // This MUST confirm actual loaded Python code. A successful
                // APK compilation and packaged wheel alone do not establish
                // that Chaquopy imports the current extractor rather than
                // the old AAR copy cached in sys.modules.
                check(VideoExtractorVersion.activate(context)){
                    "Pinned embedded Python extractor was not activated"
                }
                val runtime=Python.getInstance().getModule("yt_dlp.version")
                    .get("__version__")?.toString().orEmpty()
                check(runtime==VideoExtractorVersion.VERSION){
                    "Wrong active extractor version: $runtime"
                }
                EmbeddedYtDlpMetadata.checkRuntime()
                status("PYTHON_RUNTIME_OK version="+runtime)
                val template=File(embedded,"original.%(ext)s").absolutePath
                val exit=EmbeddedYtDlpTransfer.download(
                    url,null,null,null,embedded,template,"best")
                check(exit==0){"Embedded extractor returned nonzero exit code "+exit}
                val media=embedded.listFiles().orEmpty().filter{
                    it.isFile && !it.name.endsWith(".part")
                }.maxByOrNull{it.length()}
                    ?:error("Embedded extractor created no completed media")
                check(media.readBytes().contentEquals(payload)){
                    "Embedded extractor output did not match HTTP fixture"
                }
                status("EMBEDDED_OK "+media.length()+" bytes / exact SHA-equivalent")
                status("PASS")
                finish(0,Bundle().apply{putString("result","PASS")})
            }catch(t:Throwable){
                val chain=generateSequence(t){it.cause}
                    .take(8).joinToString(" | "){
                        it.javaClass.simpleName+": "+it.message.orEmpty().take(240)
                    }
                Log.e("ASTERION_SIM","SIMULATION FAILED",t)
                status("FAIL "+chain)
                finish(-1,Bundle().apply{putString("error",chain)})
            }finally{
                ended.set(true)
                server.close()
                serving.join(2_000)
                root.deleteRecursively()
            }
        }
    }
}
