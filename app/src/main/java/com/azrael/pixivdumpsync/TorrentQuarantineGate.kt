package com.azrael.pixivdumpsync

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Fail-closed preliminary quarantine inspection, NOT an antivirus scanner.
 * Even a file with no findings must remain private until a trusted malware
 * scanner verifies it. No public export or install is permitted here.
 */
internal object TorrentQuarantineGate {
    private val archive=setOf("zip","jar","apk","epub","xpi","docx","xlsx","pptx")
    private val risky=setOf("exe","msi","scr","com","bat","cmd","js","ps1","vbs",
        "sh","apk","jar","dll","so","dylib","class","dex","lnk","url","reg",
        "docm","xlsm","pptm","xlam","xll")
    fun preliminaryReview(file:File,relativePath:String):String {
        val issue=TorrentContentRisk.reviewReason(relativePath)
        if(issue!=null)return "REQUIRES SCAN: $issue"
        val ext=file.extension.lowercase(Locale.ROOT)
        if(ext in risky)return "REQUIRES SCAN: executable or active content"
        if(ext in archive){
            // Checking ZIP metadata is NOT execution or extraction. Bound
            // entry count and uncompressed estimates to avoid archive bombs.
            return try{
                ZipFile(file).use{zip->
                    val items=zip.entries()
                    var count=0
                    var total=0L
                    while(items.hasMoreElements()){
                        val entry=items.nextElement()
                        count++
                        if(count>20000)return "SUSPICIOUS: archive entry limit"
                        val name=entry.name.replace('\\','/')
                        if(name.startsWith("/") || name.split('/').any{it==".."})
                            return "SUSPICIOUS: archive path traversal"
                        if(TorrentContentRisk.reviewReason(name)!=null)
                            return "REQUIRES SCAN: active content inside archive"
                        if(entry.size<0L)return "REQUIRES SCAN: unknown archive size"
                        total+=entry.size
                        if(total>8L*1024*1024*1024)
                            return "SUSPICIOUS: excessive expanded archive size"
                        if(entry.compressedSize>0L &&
                            entry.size/entry.compressedSize>1000L)
                            return "SUSPICIOUS: extreme expansion ratio"
                    }
                    "PRELIMINARY ONLY: archive metadata checked; malware scan required"
                }
            }catch(t:Exception){
                "SUSPICIOUS: unreadable or malformed archive"
            }
        }
        return "PRELIMINARY ONLY: file intact; malware scan required"
    }
}
