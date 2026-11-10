package com.azrael.pixivdumpsync

import android.app.DownloadManager
import android.content.Context
import java.io.FileInputStream
import java.util.Locale

/**
 * Verify the actual bytes downloaded by Android DownloadManager before a job
 * becomes a durable completed original. STATUS_SUCCESSFUL only means the
 * system HTTP transfer ended, not that the response is the requested file.
 *
 * The validator reads a small header and ZIP trailer through the downloaded
 * file descriptor; never loads a large video, model or archive into memory.
 */
internal object OriginalFileReceipt {
    data class Check(val valid:Boolean,val reason:String,val bytes:Long=0L)
    private fun failed(why:String,n:Long=0L)=Check(false,why,n)
    private fun first(bytes:ByteArray,vararg values:Int):Boolean =
        bytes.size>=values.size && values.indices.all{
            (bytes[it].toInt() and 255)==values[it]
        }
    private fun ascii(bytes:ByteArray,offset:Int,text:String):Boolean =
        bytes.size>=offset+text.length &&
            text.indices.all{(bytes[offset+it].toInt() and 255)==text[it].code}
    private fun zipFooter(bytes:ByteArray):Boolean {
        // EOCD can have a maximum 65535-byte comment. A local ZIP header alone
        // accepts truncated or HTML-wrapped archives; the trailer is required.
        if(bytes.size<22)return false
        for(i in bytes.size-22 downTo 0){
            if((bytes[i].toInt() and 255)!=0x50 ||
                (bytes[i+1].toInt() and 255)!=0x4b ||
                (bytes[i+2].toInt() and 255)!=0x05 ||
                (bytes[i+3].toInt() and 255)!=0x06)continue
            val comment=(bytes[i+20].toInt() and 255) or
                ((bytes[i+21].toInt() and 255) shl 8)
            if(i+22+comment==bytes.size)return true
        }
        return false
    }
    internal fun inspect(filename:String,head:ByteArray,
                         tail:ByteArray,total:Long):Check {
        if(total<64L || head.size<8)return failed("Empty or truncated original",total)
        val prefix=String(head.take(300).toByteArray(),Charsets.UTF_8)
            .trimStart('\uFEFF',' ','\r','\n','\t').lowercase(Locale.ROOT)
        if(prefix.startsWith("<!doctype html") || prefix.startsWith("<html") ||
            prefix.startsWith("<head") || prefix.startsWith("<?xml") ||
            prefix.startsWith("<error") || prefix.startsWith("{\"error\"") ||
            prefix.startsWith("{\"message\"") ||
            prefix.startsWith("access denied") ||
            prefix.startsWith("forbidden")){
            return failed("Provider returned an HTML/XML error or access page",total)
        }
        val ext=filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val ok=when(ext){
            "zip","cbz","epub","apk","unitypackage" ->
                (first(head,0x50,0x4b,0x03,0x04) ||
                    first(head,0x50,0x4b,0x05,0x06)) && zipFooter(tail)
            "7z" -> first(head,0x37,0x7a,0xbc,0xaf,0x27,0x1c)
            "rar" -> first(head,0x52,0x61,0x72,0x21,0x1a,0x07)
            "png" -> first(head,0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)
            "jpg","jpeg" -> first(head,0xff,0xd8,0xff)
            "gif" -> ascii(head,0,"GIF87a") || ascii(head,0,"GIF89a")
            "webp" -> ascii(head,0,"RIFF") && ascii(head,8,"WEBP")
            "avif" -> ascii(head,4,"ftyp") &&
                (ascii(head,8,"avif") || ascii(head,8,"avis") ||
                    ascii(head,16,"avif"))
            "mp4","m4a","m4v","mov" -> ascii(head,4,"ftyp")
            "webm","mkv" -> first(head,0x1a,0x45,0xdf,0xa3)
            "avi" -> ascii(head,0,"RIFF") && ascii(head,8,"AVI ")
            "mp3" -> ascii(head,0,"ID3") ||
                (head.size>=2 && (head[0].toInt() and 255)==0xff &&
                    (head[1].toInt() and 0xe0)==0xe0)
            "flac" -> ascii(head,0,"fLaC")
            "ogg","opus" -> ascii(head,0,"OggS")
            "wav" -> ascii(head,0,"RIFF") && ascii(head,8,"WAVE")
            "pdf" -> ascii(head,0,"%PDF-")
            "psd" -> ascii(head,0,"8BPS")
            "blend" -> ascii(head,0,"BLENDER")
            "glb" -> ascii(head,0,"glTF")
            "fbx" -> ascii(head,0,"Kaydara FBX Binary  ") ||
                prefix.startsWith("; fbx ")
            "pmx" -> ascii(head,0,"PMX ")
            "pmd" -> ascii(head,0,"Pmd")
            "vrm" -> ascii(head,0,"glTF")
            "json","gltf","obj","stl","vmd","vpd","moc3","kra","clip",
            "rpa","rpy","pak","xp3","bin","torrent","model3.json",
            "motion3.json","physics3.json" -> !prefix.startsWith("<")
            else -> !prefix.startsWith("<") && total>=1024L
        }
        return if(ok)Check(true,"Verified downloaded original signature",total)
        else failed("Downloaded bytes do not match the requested original format",total)
    }
    fun verify(context:Context,manager:DownloadManager,id:Long,
               filename:String):Check {
        val uri=runCatching{manager.getUriForDownloadedFile(id)}.getOrNull()
            ?:return failed("DownloadManager returned no saved original")
        return verifyUri(context,uri,filename)
    }
    /** Also validate already-persisted MediaStore originals before passing
     * them to Rounder. No queued request or cloud-upload receipt is implied.
     */
    fun verifyUri(context:Context,uri:android.net.Uri,filename:String):Check {
        if(uri.scheme!="content")return failed("Untrusted original URI")
        return runCatching {
            context.contentResolver.openFileDescriptor(uri,"r")?.use{descriptor->
                val length=descriptor.statSize
                if(length<64L)return@use failed("Missing or unreadable saved bytes",length)
                FileInputStream(descriptor.fileDescriptor).use{input->
                    val head=ByteArray(minOf(512L,length).toInt())
                    var got=0
                    while(got<head.size){
                        val n=input.read(head,got,head.size-got)
                        if(n<0)break
                        got+=n
                    }
                    val ext=filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    val zip=ext in setOf("zip","cbz","epub","apk","unitypackage")
                    val trailer=if(zip){
                        // Android DownloadManager's descriptor is a seekable
                        // persisted file. Fail closed for ZIPs if seeking fails.
                        val n=minOf(length,65557L).toInt()
                        input.channel.position(length-n)
                        val tail=ByteArray(n)
                        var index=0
                        while(index<n){
                            val count=input.read(tail,index,n-index)
                            if(count<0)break
                            index+=count
                        }
                        tail.copyOf(index)
                    }else ByteArray(0)
                    inspect(filename,head.copyOf(got),trailer,length)
                }
            }?:failed("Saved original not accessible")
        }.getOrElse{failed("Saved-file verification unavailable: "+
            (it.javaClass.simpleName))}
    }
}
