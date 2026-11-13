package com.azrael.galleryflow

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/**
 * The main-process WebView owns user-authorized website cookies. VideoFlow
 * agents must use different WebView storage directories on Android P+; direct
 * CookieManager reads there see a DIFFERENT, often empty, jar.
 *
 * This non-exported same-UID provider retrieves cookies from the main process
 * only for sites explicitly connected by the owner. It never stores credentials,
 * never grants cross-app access and never downloads or bypasses protected media.
 */
class SessionCookieProvider:ContentProvider() {
    override fun onCreate():Boolean=true

    override fun call(method:String,arg:String?,extras:Bundle?):Bundle? {
        if(method !in setOf("read_site_cookie","read_site_html"))
            return super.call(method,arg,extras)
        if(Binder.getCallingUid()!=Process.myUid())
            throw SecurityException("Cross-application session access denied")
        val ctx=context?:return Bundle()
        val url=arg.orEmpty()
        if(!SiteSession.validLoginUrl(url) || !SiteSession.bound(ctx,url))
            return Bundle()
        if(method=="read_site_html") {
            // Connected browser verification can only be resumed in the main
            // process, which owns the login's WebView cookie jar. Binder has
            // a bounded transaction size; fail rather than truncating HTML.
            val html=com.azrael.pixivdumpsync.BrowserPageFetch.html(ctx,url)
            return Bundle().apply {
                if(html.length<=340_000)putString("html",html)
                else putString("error",
                    "Authenticated page exceeded 340,000 characters; use a "+
                    "provider-specific paginated scan")
            }
        }
        val cookie=SiteSession.cookie(url)
        return Bundle().apply{
            if(!cookie.isNullOrBlank())putString("cookie",cookie)
        }
    }

    override fun getType(uri:Uri):String?=null
    override fun query(uri:Uri,projection:Array<out String>?,
                       selection:String?,selectionArgs:Array<out String>?,
                       sortOrder:String?):Cursor?=null
    override fun insert(uri:Uri,values:ContentValues?):Uri?=null
    override fun delete(uri:Uri,selection:String?,
                        selectionArgs:Array<out String>?):Int=0
    override fun update(uri:Uri,values:ContentValues?,selection:String?,
                        selectionArgs:Array<out String>?):Int=0
}
