package com.azrael.pixivdumpsync

import java.net.URI
import java.util.Locale

/**
 * A saved X subscription must be a real, explicitly selected PROFILE.
 * /status/, /following, /photo and post URLs are not subscriptions, and
 * alternate X/Twitter hostnames must not duplicate the same owner handle.
 * Existing subscriptions and downloaded originals are never rewritten.
 */
internal object XProfileUrlPolicy {
    private val hosts=setOf("x.com","twitter.com","mobile.twitter.com",
        "fxtwitter.com","vxtwitter.com")
    private val profilePath=Regex("^/([A-Za-z0-9_]{1,15})/?$")
    private val reserved=setOf("home","explore","search","notifications",
        "messages","settings","compose","i","login","signup","privacy",
        "tos","intent","hashtag","following","followers","lists",
        "bookmarks","premium","help","account","media","status")

    data class Profile(val handle:String,val canonical:String)
    fun parse(raw:String):Profile? {
        val uri=runCatching{URI(raw.trim())}.getOrNull()?:return null
        val host=uri.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        if(uri.scheme!="https" || uri.userInfo!=null || uri.port!=-1 ||
            uri.rawQuery!=null || uri.rawFragment!=null || host !in hosts)
            return null
        val handle=profilePath.matchEntire(uri.path.orEmpty())
            ?.groupValues?.get(1)?.lowercase(Locale.ROOT)?:return null
        if(handle in reserved)return null
        return Profile(handle,"https://x.com/$handle")
    }
}
