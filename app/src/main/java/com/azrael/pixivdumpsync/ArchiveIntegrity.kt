package com.azrael.pixivdumpsync

import java.io.InputStream
import java.util.zip.ZipInputStream

/** Inspects the actual bytes of an EPUB or comic ZIP. A successful HTTP or
 * Android DownloadManager request is not evidence of a usable file.
 * The ZIP stream's CRCs are checked as every entry is fully consumed.
 */
internal object ArchiveIntegrity {
    data class Result(val entries:Int,val images:Int,val xhtml:Int)
    fun inspect(input:InputStream,format:String,
                expectedImages:Int?=null):Result {
        require(format in setOf("EPUB","ZIP"))
        ZipInputStream(input.buffered()).use{zip->
            var entries=0
            var images=0
            var xhtml=0
            var mime=false
            var opf=false
            var container=false
            val seen=HashSet<String>()
            var bytes=0L
            val buffer=ByteArray(64*1024)
            while(true){
                val entry=zip.nextEntry?:break
                if(entry.isDirectory){zip.closeEntry();continue}
                val name=entry.name.replace('\\','/')
                require(name.isNotBlank() &&
                    !name.startsWith('/') && !name.contains("../") &&
                    name !in seen){"Invalid archive entry path or duplicate"}
                seen.add(name)
                entries++
                require(entries<=100_000){"Excessive archive entry count"}
                val lower=name.lowercase()
                if(Regex("""\.(jpe?g|png|gif|webp|avif)$""").containsMatchIn(lower))
                    images++
                if(lower.endsWith(".xhtml")||lower.endsWith(".html"))xhtml++
                if(lower.endsWith(".opf"))opf=true
                if(name=="META-INF/container.xml")container=true
                val isMime=name=="mimetype"
                if(isMime)require(entries==1 && format=="EPUB"){
                    "EPUB mimetype must be first"
                }
                var mimeBytes=0
                val mimeBuf=ByteArray(20)
                while(true){
                    val n=zip.read(buffer)
                    if(n<0)break
                    bytes+=n
                    require(bytes<=16L*1024L*1024L*1024L){
                        "Archive uncompressed content exceeds 16 GiB"
                    }
                    if(isMime && mimeBytes<mimeBuf.size){
                        val copy=minOf(n,mimeBuf.size-mimeBytes)
                        System.arraycopy(buffer,0,mimeBuf,mimeBytes,copy)
                        mimeBytes+=copy
                    }
                }
                if(isMime){
                    mime=mimeBytes==mimeBuf.size &&
                        String(mimeBuf,Charsets.US_ASCII)==
                            "application/epub+zip"
                }
                zip.closeEntry()
            }
            require(entries>0){"Archive contains no entries"}
            if(format=="EPUB"){
                require(mime && opf && container && xhtml>0){
                    "Incomplete EPUB: missing mimetype, OPF, container, or chapters"
                }
            }else{
                require(images>0){"Comic ZIP contains no image pages"}
                if(expectedImages!=null)require(images==expectedImages){
                    "Comic ZIP expected $expectedImages pages, found $images"
                }
            }
            return Result(entries,images,xhtml)
        }
    }
}
