package com.azrael.pixivdumpsync

import android.content.Context
import android.net.Uri
import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.util.Locale

/**
 * A successful system DownloadManager request is NOT evidence of an original.
 * Verify actual saved bytes before any "Saved" claim, dedup completion or
 * Rounder upload. Cache successful checks by (request ID, file size).
 */
internal object OriginalReceiptVerifier {
    private const val PREF="asterion_verified_direct_originals_v1"
    private fun signature(data:ByteArray,vararg bytes:Int):Boolean=
        data.size>=bytes.size && bytes.indices.all{
            (data[it].toInt() and 255)==bytes[it]
        }

    fun verify(context:Context,id:Long,uri:Uri,name:String,
               folder:String):Boolean{
        if(id<=0L)return false
        val length=runCatching{
            context.contentResolver.openFileDescriptor(uri,"r")?.use{
                it.statSize
            }?:-1L
        }.getOrDefault(-1L)
        if(length<512L)return false
        val p=context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
        val receipt=id.toString()+":"+length
        if(p.getString(id.toString(),null)==receipt)return true
        val ext=name.substringAfterLast('.',missingDelimiterValue="")
            .lowercase(Locale.ROOT)
        val valid=runCatching {
            val input=context.contentResolver.openInputStream(uri)
                ?:error("Original file unavailable")
            input.use {stream->
                val buffered=BufferedInputStream(stream,65536)
                buffered.mark(128)
                val head=ByteArray(16)
                val n=buffered.read(head)
                require(n>=12){"Truncated original header"}
                buffered.reset()
                val text=String(head,Charsets.US_ASCII).lowercase(Locale.ROOT)
                if(text.startsWith("<html") ||
                    text.startsWith("<!doctype") ||
                    text.startsWith("{\"error") ||
                    text.startsWith("access denied"))return@use false
                when(ext){
                    "epub"->{
                        ArchiveIntegrity.inspect(buffered,"EPUB")
                        true
                    }
                    "zip"->{
                        if(folder.startsWith("Comics")){
                            ArchiveIntegrity.inspect(buffered,"ZIP")
                            true
                        }else signature(head,80,75,3,4) ||
                            signature(head,80,75,5,6)
                    }
                    "apk"->signature(head,80,75,3,4)
                    "7z"->signature(head,0x37,0x7a,0xbc,0xaf,0x27,0x1c)
                    "rar"->signature(head,0x52,0x61,0x72,0x21)
                    "png"->signature(head,137,80,78,71,13,10,26,10)
                    "jpg","jpeg"->signature(head,255,216,255)
                    "gif"->text.startsWith("gif87a")||text.startsWith("gif89a")
                    "webp"->text.startsWith("riff") &&
                        String(head,8,4,Charsets.US_ASCII)=="WEBP"
                    "avif"->String(head,4,8,Charsets.US_ASCII)
                        .contains("avif",true)
                    "mp4","m4v","mov"->String(head,4,4,Charsets.US_ASCII)=="ftyp"
                    "webm","mkv"->signature(head,0x1a,0x45,0xdf,0xa3)
                    "glb"->String(head,0,4,Charsets.US_ASCII)=="glTF"
                    "blend"->text.startsWith("blender")
                    else->true // Non-HTML binary formats remain provider-specific.
                }
            }
        }.getOrDefault(false)
        if(valid)p.edit().putString(id.toString(),receipt).apply()
        else p.edit().remove(id.toString()).apply()
        return valid
    }
}
