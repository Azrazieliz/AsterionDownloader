package com.asterion.videoflow

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Resume bytes must not remain in Android's evictable cache directory.
 * Migrate older .part, .ytdl, validators and completed staging files before
 * starting either extractor. Never delete the only existing copy.
 */
internal object VideoStaging {
    /**
     * Explicitly cancelled video only. root is the app-owned staging root,
     * never the MediaStore Downloads folder. A paused/retry job must NOT call
     * this method. Refuse path traversal, symbolic-link escapes and any other
     * job's directory.
     */
    fun discardCancelled(root:File,id:Long):Long {
        if(id<=0L || !root.isDirectory)return 0L
        val base=runCatching{root.canonicalFile}.getOrNull()?:return 0L
        val directory=File(base,id.toString())
        if(!directory.isDirectory)return 0L
        val target=runCatching{directory.canonicalFile}.getOrNull()?:return 0L
        if(target.parentFile!=base)return 0L
        var deleted=0L
        val prefix=target.path+File.separator
        for(entry in directory.walkBottomUp()){
            val resolved=runCatching{entry.canonicalPath}.getOrNull()?:continue
            if(resolved!=target.path && !resolved.startsWith(prefix))continue
            if(entry.isFile){
                val size=entry.length().coerceAtLeast(0L)
                if(entry.delete())deleted+=size
            }else if(entry.isDirectory)entry.delete()
        }
        return deleted
    }

    private fun sameBytes(first:File,second:File):Boolean{
        if(!first.isFile || !second.isFile ||
            first.length()!=second.length())return false
        fun checksum(file:File):ByteArray {
            val sha=java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use{input->
                val buf=ByteArray(256*1024)
                while(true){
                    val n=input.read(buf)
                    if(n<0)break
                    if(n>0)sha.update(buf,0,n)
                }
            }
            return sha.digest()
        }
        return runCatching{
            java.security.MessageDigest.isEqual(
                checksum(first),checksum(second))
        }.getOrDefault(false)
    }

    fun prepare(legacyCache:File, durable:File):File {
        if(!durable.isDirectory && !durable.mkdirs())
            throw IOException("Unable to create durable video staging directory")
        if(!durable.canWrite())
            throw IOException("Durable video staging directory is not writable")
        if(legacyCache.isDirectory && legacyCache.canonicalPath!=durable.canonicalPath) {
            for(old in legacyCache.listFiles().orEmpty().filter{it.isFile && it.length()>0L}) {
                val destination=File(durable,old.name)
                if(destination.isFile && destination.length()>=old.length()){
                    // Older migration versions left both complete copies.
                    // Reclaim the redundant cache file only when its exact
                    // SHA-256 equals the independently durable copy. A longer
                    // or different resumable original is never discarded.
                    if(sameBytes(old,destination))old.delete()
                    continue
                }
                val temporary=File(durable,"."+old.name+".migrating")
                if(temporary.exists() && !temporary.delete())
                    throw IOException("Unable to recover staging migration")
                try {
                    old.inputStream().buffered().use{input->
                        FileOutputStream(temporary).use{output->
                            input.copyTo(output,256*1024)
                            output.fd.sync()
                        }
                    }
                    if(temporary.length()!=old.length())
                        throw IOException("Incomplete migration of resumable video bytes")
                    if(destination.exists() && !destination.delete())
                        throw IOException("Unable to replace incomplete staging copy")
                    if(!temporary.renameTo(destination))
                        throw IOException("Unable to finalize durable staging migration")
                    // The durable copy is verified byte-for-byte before
                    // deleting the duplicate legacy cache payload.
                    if(sameBytes(old,destination))old.delete()
                } finally {
                    if(temporary.exists())temporary.delete()
                }
            }
        }
        return durable
    }
}
