package com.asterion.videoflow

import android.content.Context

/** Local, non-secret diagnostic trail per video. Does not retain cookies,
 * full URLs, signed media URLs, or extractor stdout.
 */
internal object VideoTransferJournal {
    private const val PREF="videoflow_transfer_stages_v1"
    fun record(context:Context,id:Long,stage:String,message:String=""){
        val phase=stage.take(24).filter{it.isLetterOrDigit() || it=='_'}
        val note=message.take(100).replace(Regex("""https?://\S+"""),"[url]")
        context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .edit().putString(id.toString(),phase+"|"+
                System.currentTimeMillis()+"|"+note).apply()
    }
}
