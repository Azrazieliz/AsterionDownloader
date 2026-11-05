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
    fun lastReport(context:Context):String?=
        File(context.filesDir,"last_asterion_crash.txt").takeIf{it.isFile}
            ?.readText()
}
