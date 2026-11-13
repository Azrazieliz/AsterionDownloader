package com.azrael.pixivdumpsync

import android.app.Instrumentation
import android.os.Bundle
import android.util.Log
import com.asterion.videoflow.NativeMediaDownloader
import com.asterion.videoflow.EmbeddedYtDlpMetadata
import com.asterion.videoflow.EmbeddedYtDlpTransfer
import com.asterion.videoflow.LegacyYtDlpTransfer
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
                            while(true){if(reader.readLine().isNullOrEmpty())break}
                            val out=socket.getOutputStream()
                            val header=("HTTP/1.1 200 OK\r\n"+
                                "Content-Type: video/mp4\r\nContent-Length: "+
                                payload.size+"\r\nConnection: close\r\n\r\n")
                                .toByteArray(Charsets.US_ASCII)
                            out.write(header)
                            if(!request.startsWith("HEAD "))out.write(payload)
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
                val embedded=File(root,"chaquopy").apply{mkdirs()}
                Log.i("ASTERION_SIM","AST_SIM: PYTHON_BEGIN")
                LegacyYtDlpTransfer.initialize(context)
                EmbeddedYtDlpMetadata.checkRuntime()
                status("PYTHON_RUNTIME_OK")
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
