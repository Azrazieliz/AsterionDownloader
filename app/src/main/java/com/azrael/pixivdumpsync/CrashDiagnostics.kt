package com.azrael.pixivdumpsync

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/** Preserve the actual Java crash rather than guessing from a video.
 * Delegates to Android's original crash handler after recording diagnostics.
 */
object CrashDiagnostics {
    @Volatile private var installed=false
    fun install(context:Context){
        if(installed)return
        synchronized(this){
            if(installed)return
            val app=context.applicationContext
            val previous=Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler{worker,error->
                runCatching{
                    val stack=StringWriter().also{
                        error.printStackTrace(PrintWriter(it))
                    }.toString().take(48_000)
                    File(app.filesDir,"last_asterion_crash.txt").writeText(
                        "Time: "+Date()+"\n"+
                        "Thread: "+worker.name+"\n"+
                        "Android: "+android.os.Build.VERSION.RELEASE+"\n"+
                        "App process: "+app.packageName+"\n"+stack)
                }
                previous?.uncaughtException(worker,error)
            }
            installed=true
        }
    }
    /**
     * A background process can be killed by Android without invoking the
     * Java uncaught-exception handler. Read Android 11+ exit records too:
     * native crashes, ANRs and low-memory kills cannot be guessed from a
     * generic "has stopped" screenshot.
     */
    fun lastReport(context:Context):String? {
        val local=runCatching{
            File(context.filesDir,"last_asterion_crash.txt")
                .takeIf{it.isFile}?.readText()
        }.getOrNull()
        val os=if(android.os.Build.VERSION.SDK_INT>=30)runCatching{
            val manager=context.getSystemService(
                android.app.ActivityManager::class.java)
            val exit=manager.getHistoricalProcessExitReasons(
                context.packageName,0,12).firstOrNull{item->
                item.reason in setOf(
                    android.app.ApplicationExitInfo.REASON_CRASH,
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE,
                    android.app.ApplicationExitInfo.REASON_ANR,
                    android.app.ApplicationExitInfo.REASON_LOW_MEMORY)
            }
            exit?.let{item->
                val type=when(item.reason){
                    android.app.ApplicationExitInfo.REASON_CRASH->"Java crash"
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE->"Native crash"
                    android.app.ApplicationExitInfo.REASON_ANR->"ANR"
                    android.app.ApplicationExitInfo.REASON_LOW_MEMORY->"Low memory kill"
                    else->"Unexpected process exit"
                }
                val trace=runCatching{
                    item.traceInputStream?.bufferedReader()?.use{
                        it.readText().take(24_000)
                    }
                }.getOrNull().orEmpty()
                "Android recorded: "+type+
                    "\nTimestamp: "+java.util.Date(item.timestamp)+
                    "\nProcess: "+item.processName+
                    "\nDescription: "+item.description.orEmpty().take(500)+
                    (if(trace.isNotBlank())"\nAndroid trace:\n"+trace else "")
            }
        }.getOrNull() else null
        if(local.isNullOrBlank())return os
        if(os.isNullOrBlank())return local
        // Preserve both sources; a native crash or system ANR can happen
        // after an unrelated old Java stack trace.
        return os+"\n\nPrevious Java exception record:\n"+local
    }
    fun safeForClipboard(context:Context):String?=
        lastReport(context)?.replace(
            Regex("""(?i)https?://[^\s'\\"<>]+"""),"[URL redacted]")
            ?.replace(Regex(
                """(?i)(?:cookie|authorization|token|password|signature)\s*[:=]\s*[^\s;,]+"""),
                "[credential redacted]")?.take(48_000)
}
