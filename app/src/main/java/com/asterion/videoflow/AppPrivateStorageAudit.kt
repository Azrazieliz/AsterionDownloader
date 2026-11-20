package com.asterion.videoflow

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

/**
 * Read-only bounded sizing of app-private files. This never removes a file,
 * walks outside canonical app roots, follows a symbolic-link target or
 * exports an actual user filename. Counts can be partial when the time budget
 * is reached; never imply the sum is exact in that case.
 */
internal object AppPrivateStorageAudit {
    data class Bucket(val bytes:Long,val files:Int)
    data class Scan(val buckets:Map<String,Bucket>,
                    val inspectedFiles:Int,val complete:Boolean)

    private fun label(relative:String):String {
        val parts=relative.replace('\\','/').split('/')
            .filter{it.isNotBlank()}
        val chosen=parts.take(if(parts.firstOrNull() in
            setOf("files","cache","external-files","external-cache"))2 else 1)
        return chosen.joinToString("/").replace(
            Regex("[^A-Za-z0-9_./-]"),"_").take(72).ifBlank{"root"}
    }

    fun scan(root:File,kind:String,maxFiles:Int=40000,
             maxMillis:Long=1800L):Scan {
        if(!root.isDirectory)return Scan(emptyMap(),0,true)
        val canonical=runCatching{root.canonicalPath}.getOrNull()
            ?:return Scan(emptyMap(),0,false)
        val stack=ArrayDeque<File>()
        root.listFiles()?.forEach{stack.addLast(it)}
        val counts=LinkedHashMap<String,Bucket>()
        val started=SystemClock.elapsedRealtime()
        var inspected=0
        while(stack.isNotEmpty() && inspected<maxFiles &&
            SystemClock.elapsedRealtime()-started<maxMillis){
            val item=stack.removeLast()
            val absolute=runCatching{item.canonicalPath}.getOrNull()?:continue
            if(!absolute.startsWith(canonical+File.separator))continue
            if(item.isDirectory){
                item.listFiles()?.forEach{stack.addLast(it)}
            }else if(item.isFile){
                val rel=absolute.substring(canonical.length+1)
                val group=kind+"/"+label(rel)
                val prev=counts[group]?:Bucket(0L,0)
                counts[group]=Bucket(prev.bytes+item.length().coerceAtLeast(0L),
                    prev.files+1)
                inspected++
            }
        }
        return Scan(counts,inspected,stack.isEmpty())
    }

    fun report(context:Context):String {
        val roots=listOfNotNull(
            "internal" to File(context.applicationInfo.dataDir),
            context.getExternalFilesDir(null)?.let{"external-files" to it},
            context.externalCacheDir?.let{"external-cache" to it})
        val collected=LinkedHashMap<String,Bucket>()
        var partial=false
        var examined=0
        for((label,path) in roots){
            val scanned=scan(path,label)
            collected.putAll(scanned.buckets)
            examined+=scanned.inspectedFiles
            if(!scanned.complete)partial=true
        }
        val sum=collected.values.sumOf{it.bytes}
        return buildString{
            append("APP-PRIVATE STORAGE (READ-ONLY")
            if(partial)append(", PARTIAL") else append(", SCANNED")
            append(")\n")
            append("Counted bytes: ").append(sum).append("\n")
            append("Counted files: ").append(examined).append("\n")
            for((name,amount) in collected.entries
                .sortedByDescending{it.value.bytes}.take(18)){
                append(name).append(": ")
                    .append(String.format(Locale.US,"%.1f MiB",
                        amount.bytes.toDouble()/(1024*1024)))
                    .append(" / ").append(amount.files).append(" files\n")
            }
            append("Public MediaStore originals are excluded. ")
            append("No files were deleted.\n")
        }
    }
}
