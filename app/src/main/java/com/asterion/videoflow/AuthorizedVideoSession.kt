package com.asterion.videoflow

import android.content.Context
import com.azrael.galleryflow.SiteSession
import java.io.File
import java.net.URI

/** Reuse only cookies for a site the user explicitly connected inside the app.
 * Each temporary Netscape cookie file stays in private cache and is deleted
 * immediately after the extractor attempts. No third-party paywall bypass. */
internal object AuthorizedVideoSession {
    fun temporaryCookies(context:Context,videoUrl:String):File? {
        if(!SiteSession.bound(context,videoUrl)) return null
        val uri=URI(videoUrl)
        if(uri.scheme!="https")return null
        val host=uri.host.orEmpty().lowercase().removePrefix("www.")
        if(host.isBlank()||host.contains("\t")||host.contains("\n"))return null
        val cookie=SiteSession.cookie(videoUrl).orEmpty()
        val rows=cookie.split(';').mapNotNull { part->
            val token=part.trim()
            val eq=token.indexOf('=')
            if(eq<=0)return@mapNotNull null
            val name=token.substring(0,eq).trim()
            val value=token.substring(eq+1).trim()
            if(!name.matches(Regex("[A-Za-z0-9_!#$%&'*+.^\u0060|~-]{1,128}")) ||
                value.any{it=='\n'||it=='\r'||it=='\t'})return@mapNotNull null
            "$host\tFALSE\t/\tTRUE\t0\t$name\t$value"
        }
        if(rows.isEmpty())return null
        val file=File.createTempFile("asterion-session-", ".txt", context.cacheDir)
        file.setReadable(false,false)
        file.setWritable(false,false)
        file.setReadable(true,true)
        file.setWritable(true,true)
        return try {
            file.writeText(("# Netscape HTTP Cookie File\n"+
                rows.joinToString("\n")+"\n"))
            file
        }catch(t:Exception){file.delete();throw t}
    }
}
