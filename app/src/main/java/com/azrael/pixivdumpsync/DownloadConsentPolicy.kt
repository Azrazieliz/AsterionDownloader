package com.azrael.pixivdumpsync

/** A site's discovery record is never consent to transfer its entire catalog.
 * Original files can be queued from an explicitly selected item or a
 * deliberately enabled site. Entire books, comics, games and assets require
 * individual selection regardless of ambient website indexing.
 */
object DownloadConsentPolicy {
    private val selectedOnly=setOf(
        "BOOKS","COMICS","GAMES","ASSETS","MODS","VOICE",
        "STUDIO","MMD","LIVE2D","ANIMATION"
    )
    fun automaticAllowed(area:String,sourceExplicitlyEnabled:Boolean):Boolean =
        sourceExplicitlyEnabled && area !in selectedOnly
}
