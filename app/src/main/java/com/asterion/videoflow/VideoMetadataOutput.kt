package com.asterion.videoflow

import org.json.JSONObject

/** yt-dlp stdout may contain console lines and multi-line JSON. */
internal object VideoMetadataOutput {
    fun parse(output:String):JSONObject? {
        var start=-1
        var depth=0
        var inString=false
        var escaped=false
        for(i in output.indices){
            val ch=output[i]
            if(start<0){
                if(ch=='{'){start=i;depth=1;inString=false;escaped=false}
                continue
            }
            if(inString){
                if(escaped){escaped=false;continue}
                when(ch){
                    '\\'->escaped=true
                    '"'->inString=false
                }
                continue
            }
            when(ch){
                '"'->inString=true
                '{'->depth++
                '}'->{
                    depth--
                    if(depth==0){
                        val obj=runCatching{
                            JSONObject(output.substring(start,i+1))
                        }.getOrNull()
                        if(obj!=null && (obj.has("id") || obj.has("title") ||
                                obj.has("duration") || obj.has("formats")))return obj
                        start=-1
                    }
                }
            }
        }
        return null
    }
}
