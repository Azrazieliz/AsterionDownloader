package com.azrael.pixivdumpsync

import android.app.Application
import android.os.Build
import android.util.Log
import android.webkit.WebView

/**
 * Android P+ requires every process using WebView to own a separate data
 * directory. Both VideoFlow agents are isolated processes; their provider
 * browser fallback must not compete with the main application's WebView.
 */
class AsterionApplication:Application() {
    override fun onCreate() {
        super.onCreate()
        CrashDiagnostics.install(this)
        if(Build.VERSION.SDK_INT>=28) {
            val process=Application.getProcessName()
            if(process!=packageName) {
                val suffix=process.substringAfter(':',process)
                    .replace(Regex("[^a-zA-Z0-9_-]"),"_").take(80)
                runCatching { WebView.setDataDirectorySuffix(suffix) }
                    .onFailure { Log.e("AsterionWebView",
                        "Could not isolate provider browser data in "+process,it) }
            }
        }
    }
}
