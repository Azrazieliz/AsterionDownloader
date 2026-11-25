package com.asterion.videoflow

import android.content.Context
import com.azrael.galleryflow.SiteSession
import java.io.File
import java.net.URI

/** Reuse only cookies for a site the user explicitly connected inside the app.
 * Each temporary Netscape cookie file stays in private cache and is deleted
 * immediately after the extractor attempts. No third-party paywall bypass. */
internal object AuthorizedVideoSession {
    /** A Netscape host-only cookie must name the actual request host.
     * Removing www from the host while keeping FALSE (no subdomains)
     * silently drops the explicitly connected session on www URLs.
     * Do not broaden the cookie's domain or add any other host. */
    internal fun cookieHost(videoUrl:String):String? {
        val uri=runCatching{URI(videoUrl)}.getOrNull()?:return null
        if(uri.scheme!="https" || uri.userInfo!=null || uri.port!=-1)return null
        val host=uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
        return host.takeIf{it.isNotBlank() &&
            it.matches(Regex("[a-z0-9.-]+"))}
    }

    fun temporaryCookies(context:Context,videoUrl:String):File? {
        if(!SiteSession.bound(context,videoUrl)) return null
        val host=cookieHost(videoUrl)?:return null
        val cookie=SiteSession.cookie(context,videoUrl).orEmpty()
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
