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
    fun prepare(legacyCache:File, durable:File):File {
        if(!durable.isDirectory && !durable.mkdirs())
            throw IOException("Unable to create durable video staging directory")
        if(!durable.canWrite())
            throw IOException("Durable video staging directory is not writable")
        if(legacyCache.isDirectory && legacyCache.canonicalPath!=durable.canonicalPath) {
            for(old in legacyCache.listFiles().orEmpty().filter{it.isFile && it.length()>0L}) {
                val destination=File(durable,old.name)
                if(destination.isFile && destination.length()>=old.length())continue
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
                } finally {
                    if(temporary.exists())temporary.delete()
                }
            }
        }
        return durable
    }
}
