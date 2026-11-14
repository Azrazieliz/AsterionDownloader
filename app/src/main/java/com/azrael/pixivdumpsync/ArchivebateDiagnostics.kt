package com.azrael.pixivdumpsync

import android.content.Context
import java.util.Locale

/** Selected Archivebate player diagnostics, retained locally without media
 * URLs, cookies, tokenized links, account details or sensitive headers.
 */
internal object ArchivebateDiagnostics {
    private const val STORE="archivebate_failure_diagnostics_v1"
    private val urls=Regex("""(?i)https?://[^\s'"<>]+""")
    private val secrets=Regex(
        """(?i)(?:cookie|authorization|bearer|token|password|signature|apikey|api_key)\s*[:=]\s*[^\s;,]+""")
    private fun clean(raw:String)=raw.replace(urls,"[media URL redacted]")
        .replace(secrets,"[credential redacted]")
        .replace(Regex("""[\r\n\t|]+""")," ")
        .trim().take(220)

    fun record(context:Context,stage:String,reason:String="") {
        val safeStage=stage.lowercase(Locale.ROOT)
            .replace(Regex("""[^a-z0-9_-]"""),"_").take(35)
        val row=System.currentTimeMillis().toString()+"|"+safeStage+"|"+clean(reason)
        val prefs=context.applicationContext.getSharedPreferences(STORE,Context.MODE_PRIVATE)
        val lines=prefs.getString("events","").orEmpty().lineSequence()
            .filter{it.isNotBlank()}.takeLastSafely(19)+row
        prefs.edit().putString("events",lines.joinToString("\n")).apply()
    }
    private fun Sequence<String>.takeLastSafely(max:Int):List<String> =
        this.toList().takeLast(max)
    fun report(context:Context):String=buildString {
        appendLine("[ARCHIVEBATE SELECTED-PLAYER DIAGNOSTICS]")
        appendLine("Active provider; selected-player events from this installed build follow.")
        val saved=context.applicationContext.getSharedPreferences(STORE,Context.MODE_PRIVATE)
            .getString("events","").orEmpty().lineSequence().filter{it.isNotBlank()}.toList()
        if(saved.isEmpty())appendLine("No selected-player diagnostic event recorded by this build")
        else for(row in saved)appendLine(row)
    }
}
