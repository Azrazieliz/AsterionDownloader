package com.asterion.videoflow

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Crash-safe receipt for a completely finalized NATIVE staged media file.
 * Nonempty files from older releases or interrupted sessions are NOT proof
 * that all original bytes arrived. The receipt binds exact length and SHA256
 * to the finalized bytes and is never used as a web identity claim.
 */
internal object NativeMediaReceipt {
    /** Never buffer full originals. Compare the exact bytes published to
     * MediaStore against the bytes read from selected, validated staging.
     * A matching file length or HTTP 200 is not sufficient for a receipt.
     */
    internal fun copyAndDigest(input:InputStream,output:OutputStream):ByteArray {
        val hash=MessageDigest.getInstance("SHA-256")
        val buffer=ByteArray(256*1024)
        while(true){
            val count=input.read(buffer)
            if(count<0)break
            if(count==0)continue
            output.write(buffer,0,count)
            hash.update(buffer,0,count)
        }
        output.flush()
        return hash.digest()
    }

    internal fun digestStream(input:InputStream):ByteArray {
        val hash=MessageDigest.getInstance("SHA-256")
        val buffer=ByteArray(256*1024)
        while(true){
            val count=input.read(buffer)
            if(count<0)break
            if(count>0)hash.update(buffer,0,count)
        }
        return hash.digest()
    }

    internal fun matchesSavedCopy(input:InputStream,expected:ByteArray):Boolean =
        expected.size==32 && MessageDigest.isEqual(digestStream(input),expected)

    private fun digest(file:File):String {
        val d=MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(131_072).use{input->
            val buffer=ByteArray(131_072)
            while(true){
                val size=input.read(buffer)
                if(size<0)break
                if(size>0)d.update(buffer,0,size)
            }
        }
        return d.digest().joinToString(""){"%02x".format(it)}
    }
    private fun payload(file:File):String =
        file.length().toString()+"|"+digest(file)+"\n"

    fun matches(media:File,receipt:File):Boolean=runCatching{
        if(!media.isFile || media.length()<=0L || !receipt.isFile)
            return@runCatching false
        val contents=receipt.readText(Charsets.US_ASCII)
        val parts=contents.trim().split('|')
        if(parts.size!=2 || parts[0].toLongOrNull()!=media.length() ||
            !parts[1].matches(Regex("[0-9a-f]{64}")))return@runCatching false
        // The checksum is computed fresh: a resumed/stale/incomplete file
        // cannot impersonate the previous completed transfer.
        contents==payload(media)
    }.getOrDefault(false)

    fun write(media:File,receipt:File){
        require(media.isFile && media.length()>0L){
            "Cannot attest an empty or missing original media file"
        }
        require(receipt.parentFile==media.parentFile){
            "Receipt must share the media staging directory"
        }
        val temporary=File(receipt.parentFile,receipt.name+".tmp")
        try {
            temporary.writeText(payload(media),Charsets.US_ASCII)
            check(temporary.length()>0L){"Native media receipt was empty"}
            if(receipt.exists() && !receipt.delete())
                error("Could not replace stale media receipt")
            check(temporary.renameTo(receipt)){
                "Could not commit completed native media receipt"
            }
        }finally{temporary.delete()}
    }
}
