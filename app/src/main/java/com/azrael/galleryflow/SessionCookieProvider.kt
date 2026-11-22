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
        if(method !in setOf("read_site_cookie","read_site_html",
                "is_site_connected","resolve_archivebate_selected"))
            return super.call(method,arg,extras)
        if(Binder.getCallingUid()!=Process.myUid())
            throw SecurityException("Cross-application session access denied")
        val ctx=context?:return Bundle()
        val url=arg.orEmpty()
        if(!SiteSession.validLoginUrl(url))return Bundle()
        val connected=SiteSession.bound(ctx,url)
        if(method=="is_site_connected")return Bundle().apply{
            putBoolean("connected",connected)
        }
        // Archivebate's exactly selected public Mixdrop player is not a
        // private login/session read. Agent 2 runs in an isolated process,
        // so the previous blanket login gate rejected every public recorded
        // watch before reaching the verified publisher player. The next
        // branch still accepts ONLY ArchivebateSelectedMedia.isSelectedEmbed.
        if(method=="resolve_archivebate_selected"){
            val embed=extras?.getString("embed").orEmpty()
            // Read only the user-selected watch page's own published player.
            // Never expose arbitrary URL fetching through Binder.
            if(!com.asterion.videoflow.ArchivebateSelectedMedia
                    .isSelectedEmbed(url,embed))
                return Bundle().apply{putString("error","Invalid selected player")}
            val result=runCatching{
                com.asterion.videoflow.ArchivebateSelectedMedia
                    .fromConnectedPlayer(ctx,url,embed)
            }
            return Bundle().apply{
                result.onSuccess{putString("media",it)}
                    .onFailure{putString("error",
                        it.message.orEmpty().take(180))}
            }
        }
        // The owner-process browser must also inspect the exact
        // first-party player frame attested by one selected publisher page.
        // This does NOT grant a generic cross-site HTML proxy: the selected
        // parent, child host and child route must all match explicit policy.
        val selectedReferer=extras?.getString("referer").orEmpty()
        val authorizedFrame=method=="read_site_html" &&
            com.azrael.pixivdumpsync.BrowserPageFetch
                .publicEligible(selectedReferer) &&
            com.asterion.videoflow.SiteSpecificOriginalSources
                .selectedExtractorFrame(selectedReferer,url)
        if(!connected &&
            !(method=="read_site_html" &&
                (com.azrael.pixivdumpsync.BrowserPageFetch
                    .publicEligible(url) || authorizedFrame)))
            return Bundle()
        if(method=="read_site_html") {
            // Exact public selected players also need the owner's main
            // process WebView cookie jar. The isolated VideoFlow processes
            // have separate Android P+ WebView storage partitions.
            val referer=extras?.getString("referer")?.takeIf{
                com.azrael.pixivdumpsync.BrowserPageFetch.publicEligible(it) ||
                    SiteSession.bound(ctx,it)
            }
            val html=com.azrael.pixivdumpsync.BrowserPageFetch.html(
                ctx,url,playerReferer=referer)
            return Bundle().apply {
                if(html.toByteArray(Charsets.UTF_8).size<=SessionHtmlEnvelope.RAW_LIMIT)
                    putString("html",html)
                else {
                    val packed=SessionHtmlEnvelope.compress(html)
                    if(packed!=null)putString("html_gzip",packed)
                    else putString("error",
                        "Authenticated page exceeds safe Binder transfer limits; "+
                        "provider must expose a smaller selected-player snapshot")
                }
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
