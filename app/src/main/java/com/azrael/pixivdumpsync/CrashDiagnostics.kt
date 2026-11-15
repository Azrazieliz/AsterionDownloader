package com.azrael.pixivdumpsync

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.util.AtomicFile
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/**
 * Crash capture is installed in EVERY Android process (including VideoFlow).
 * Separate atomic files retain the original process identity instead of
 * allowing one agent's exception to overwrite another's report.
 */
object CrashDiagnostics {
    @Volatile private var installed=false
    private const val DIRECTORY="crash-diagnostics-v2"
    private fun id(process:String):String =
        process.substringAfterLast(':').replace(Regex("[^A-Za-z0-9_-]"),"_")
            .take(70).ifBlank{"main"}
    fun install(context:Context){
        if(installed)return
        synchronized(this){
            if(installed)return
            val app=context.applicationContext
            val previous=Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler{worker,error->
                runCatching{
                    val process=Application.getProcessName().orEmpty()
                        .ifBlank{app.packageName}
                    val stack=StringWriter().also{
                        error.printStackTrace(PrintWriter(it))
                    }.toString().take(48_000)
                    val folder=File(app.filesDir,DIRECTORY).apply{mkdirs()}
                    val atomic=AtomicFile(File(folder,id(process)+".txt"))
                    val handle=atomic.startWrite()
                    try{
                        handle.write((
                            "Time: "+Date()+"\n"+
                            "Thread: "+worker.name+"\n"+
                            "Android: "+Build.VERSION.RELEASE+"\n"+
                            "Actual process: "+process+"\n"+stack
                        ).toByteArray(Charsets.UTF_8))
                        atomic.finishWrite(handle)
                    }catch(t:Throwable){
                        atomic.failWrite(handle)
                        throw t
                    }
                }
                previous?.uncaughtException(worker,error)
            }
            installed=true
        }
    }
    /**
     * Read Java exceptions plus Android's historical native-crash, ANR
     * and low-memory kill reports. A system kill never invokes Java's
     * Thread.UncaughtExceptionHandler.
     */
    fun lastReport(context:Context):String?{
        val local=runCatching{
            File(context.filesDir,DIRECTORY).listFiles()
                .orEmpty().asSequence()
                .filter{it.isFile && it.name.endsWith(".txt")}
                .sortedByDescending{it.lastModified()}.take(6)
                .map{it.readText().take(12_000)}.toList()
                .joinToString("\n\n--- NEXT PROCESS ---\n\n")
        }.getOrDefault("")
        val legacy=runCatching{
            File(context.filesDir,"last_asterion_crash.txt")
                .takeIf{it.isFile}?.readText()?.take(12_000)
        }.getOrNull().orEmpty()
        val os=if(Build.VERSION.SDK_INT>=30)runCatching{
            val manager=context.getSystemService(ActivityManager::class.java)
            manager?.getHistoricalProcessExitReasons(
                context.packageName,0,30).orEmpty()
                .filter{it.reason in setOf(
                    android.app.ApplicationExitInfo.REASON_CRASH,
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE,
                    android.app.ApplicationExitInfo.REASON_ANR,
                    android.app.ApplicationExitInfo.REASON_LOW_MEMORY)}
                .take(5).map{item->
                    val type=when(item.reason){
                        android.app.ApplicationExitInfo.REASON_CRASH->"Java crash"
                        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE->"Native crash"
                        android.app.ApplicationExitInfo.REASON_ANR->"ANR"
                        android.app.ApplicationExitInfo.REASON_LOW_MEMORY->"Low memory kill"
                        else->"Unexpected process exit"
                    }
                    val trace=runCatching{
                        item.traceInputStream?.bufferedReader()?.use{
                            it.readText().take(12_000)
                        }
                    }.getOrNull().orEmpty()
                    "Android recorded: "+type+
                        "\nTimestamp: "+Date(item.timestamp)+
                        "\nProcess: "+item.processName+
                        "\nDescription: "+item.description.orEmpty().take(500)+
                        (if(trace.isNotBlank())"\nAndroid trace:\n"+trace else "")
                }.joinToString("\n\n--- NEXT SYSTEM EXIT ---\n\n")
        }.getOrDefault("") else ""
        val sources=listOf(
            os.takeIf{it.isNotBlank()}?.let{"Android process exits:\n"+it},
            local.takeIf{it.isNotBlank()}?.let{"Per-process Java crash files:\n"+it},
            legacy.takeIf{it.isNotBlank()}?.let{"Legacy Java crash file:\n"+it}
        ).filterNotNull()
        return sources.joinToString("\n\n").ifBlank{null}
    }
    fun safeForClipboard(context:Context):String?=
        lastReport(context)?.replace(
            Regex("""(?i)https?://[^\s'\\"<>]+"""),"[URL redacted]")
            ?.replace(Regex(
                """(?i)(?:cookie|authorization|token|password|signature)\s*[:=]\s*[^\s;,]+"""),
                "[credential redacted]")?.take(48_000)
}
