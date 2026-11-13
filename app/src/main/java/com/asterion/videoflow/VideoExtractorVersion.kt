package com.asterion.videoflow

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import java.io.File
import java.security.MessageDigest

/** Offline, checksum-pinned update to the yt-dlp version bundled by the
 * upstream Android AAR (2026.6.9). No runtime pip, network, shell, or
 * self-updater; the APK already contains the full verified Python wheel.
 *
 * A missing/corrupt asset leaves the original working yt-dlp untouched,
 * rather than breaking providers already supported by the old engine.
 */
internal object VideoExtractorVersion {
    const val VERSION="2026.10.26"
    const val ASSET="yt_dlp-2026.10.26-py3-none-any.whl"
    const val SHA256="a05c5b5d8562f7e8a62f5334348b406af4930d56d3a65f8021d14af0e22339ae"
    @Volatile private var activated=false
    @Volatile private var attempted=false

    private fun hash(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use{input->
            val buf=ByteArray(131072)
            while(true){
                val read=input.read(buf)
                if(read<0)break
                digest.update(buf,0,read)
            }
        }
        return digest.digest().joinToString(""){"%02x".format(it)}
    }

    @Synchronized fun activate(context:Context):Boolean {
        if(attempted)return activated
        attempted=true
        val app=context.applicationContext
        val root=File(app.filesDir,"video-extractor-version").apply{mkdirs()}
        val wheel=File(root,ASSET)
        try{
            if(!wheel.isFile || hash(wheel)!=SHA256){
                val staged=File(root,ASSET+".tmp")
                try{
                    app.assets.open(ASSET).use{input->
                        staged.outputStream().buffered().use{output->
                            input.copyTo(output,131072)
                        }
                    }
                    check(hash(staged)==SHA256){"Official extractor wheel checksum mismatch"}
                    if(wheel.exists())check(wheel.delete()){"Cannot replace old wheel"}
                    check(staged.renameTo(wheel)){"Cannot install embedded extractor wheel"}
                }finally{staged.delete()}
            }
            val python=Python.getInstance()
            val path=python.getModule("sys").get("path")
                ?:error("Python sys.path unavailable")
            // zipimport supports .whl archives containing pure Python sources.
            // This runs before the upstream ytdlp_runner first imports yt_dlp.
            path.callAttr("insert",0,wheel.absolutePath)
            val version=python.getModule("yt_dlp.version")
                .get("__version__")?.toString().orEmpty()
            check(version==VERSION){
                "Unexpected yt-dlp version "+version
            }
            activated=true
            Log.i("AsterionVideo","Bundled yt-dlp "+VERSION+" active")
        }catch(t:Exception){
            // Preserve the old AAR's working extractor as a compatibility
            // fallback if the wheel was corrupt or unavailable. Do not report
            // success or keep the incomplete path override.
            runCatching{
                val sys=Python.getInstance().getModule("sys")
                sys.get("path")?.callAttr("remove",wheel.absolutePath)
            }
            Log.w("AsterionVideo",
                "Updated embedded extractor unavailable; using original AAR version",t)
        }
        return activated
    }
}
