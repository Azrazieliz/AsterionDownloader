package com.asterion.videoflow

import android.content.Context

/** Persist a sanitized Python startup failure without failing 39 unrelated
 * queued originals. Per-process failures are visible from the main UI.
 */
internal object VideoRuntimeState {
    private const val PREF="asterion_videoflow_runtime_health_v1"
    private const val KEY="startup_failure"
    fun healthy(c:Context){
        c.getSharedPreferences(PREF,0).edit().remove(KEY).apply()
    }
    fun failure(c:Context):String?=
        c.getSharedPreferences(PREF,0).getString(KEY,null)
    fun failed(c:Context,t:Throwable):String{
        val chain=generateSequence(t){it.cause}.take(5).joinToString(" <- "){
            it.javaClass.simpleName+":"+it.message.orEmpty()
                .replace(Regex("""(?i)https?://[^\s]+"""),"[redacted URL]")
                .take(110)
        }.take(380)
        c.getSharedPreferences(PREF,0).edit().putString(KEY,chain).apply()
        return chain
    }
}
