package com.asterion.videoflow

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Cross-process transfer progress. The main UI, primary and secondary agents
 * run in DIFFERENT Android processes. SharedPreferences caches its values by
 * process, so a transfer showing 7% in SQLite was displayed as "Preparing".
 *
 * One atomic, private file per download ID is read fresh by every process.
 * Stage changes preserve the last verified on-disk media byte count.
 * No URL, cookies or credentials are persisted here.
 */
internal object VideoTransferJournal {
    private const val DIR="videoflow-transfer-stages-v2"
    data class Snapshot(
        val stage:String,val updated:Long,val detail:String,val verifiedBytes:Long=0L
    )
    private fun file(context:Context,id:Long)=AtomicFile(
        File(File(context.filesDir,DIR).apply{mkdirs()},"$id.stage")
    )
    internal fun decode(raw:String):Snapshot? {
        val fields=raw.split('|',limit=4)
        if(fields.size!=4)return null
        val updated=fields[1].toLongOrNull()?:return null
        val bytes=fields[3].toLongOrNull()?.takeIf{it>=0L}?:return null
        val stage=fields[0].takeIf{
            it.isNotBlank()&&it.length<=24&&it.all{c->
                c.isLetterOrDigit()||c=='_'
            }
        }?:return null
        return Snapshot(stage,updated,fields[2],bytes)
    }
    private fun encode(item:Snapshot):ByteArray =
        (item.stage+"|"+item.updated+"|"+item.detail+"|"+
            item.verifiedBytes+"\n").toByteArray(StandardCharsets.UTF_8)
    fun current(context:Context,id:Long):Snapshot? =
        runCatching {
            val atomic=file(context,id)
            if(!atomic.baseFile.exists())null
            else decode(String(atomic.readFully(),StandardCharsets.UTF_8).trimEnd())
        }.getOrNull()

    fun record(context:Context,id:Long,stage:String,message:String=""){
        val phase=stage.take(24).filter{it.isLetterOrDigit()||it=='_'}
        if(phase.isBlank())return
        val note=message.take(100)
            .replace(Regex("""(?i)https?://\S+"""),"[url]")
            .replace(Regex("""[\r\n|]""")," ")
        val previous=current(context,id)?.verifiedBytes?:0L
        // On a fresh attempt, do not call old partial bytes "new transfer".
        val bytes=when(phase){
            "preparing"->0L
            "receiving"->maxOf(previous,note.toLongOrNull()?.coerceAtLeast(0L)?:0L)
            else->previous
        }
        val atomic=file(context,id)
        val out=atomic.startWrite()
        try{
            out.write(encode(Snapshot(phase,System.currentTimeMillis(),note,bytes)))
            atomic.finishWrite(out)
        }catch(t:Throwable){
            atomic.failWrite(out)
            throw t
        }
    }
}
