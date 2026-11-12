package com.asterion.videoflow

import android.content.Context

/** Local, non-secret diagnostic trail per video. Does not retain cookies,
 * full URLs, signed media URLs, or extractor stdout.
 */
internal object VideoTransferJournal {
    private const val PREF="videoflow_transfer_stages_v1"
    data class Snapshot(val stage:String,val updated:Long,val detail:String)
    fun current(context:Context,id:Long):Snapshot? {
        val raw=context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .getString(id.toString(),null)?:return null
        val fields=raw.split('|',limit=3)
        if(fields.size<3)return null
        return Snapshot(fields[0],fields[1].toLongOrNull()?:0L,fields[2])
    }
    fun record(context:Context,id:Long,stage:String,message:String=""){
        val phase=stage.take(24).filter{it.isLetterOrDigit() || it=='_'}
        val note=message.take(100).replace(Regex("""https?://\S+"""),"[url]")
        context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .edit().putString(id.toString(),phase+"|"+
                System.currentTimeMillis()+"|"+note).apply()
    }
}
