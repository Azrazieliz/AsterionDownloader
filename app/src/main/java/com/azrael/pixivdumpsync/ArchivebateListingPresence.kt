package com.azrael.pixivdumpsync

/** Recognize actual selected Archivebate watch links in the already-rendered
 * publisher DOM. Do not wait for six extra polls when the canonical public
 * catalog uses /u/watch/<id>, www, or absolute HTTPS hrefs.
 */
internal object ArchivebateListingPresence {
    private val watch=Regex(
        """(?i)\\bhref\\s*=\\s*["'](?:https://(?:www\\.)?archivebate\\.com)?/(?:u/)?watch/[0-9]{1,16}/?(?:[?#][^"'<>]*)?["']"""
    )
    fun hasSelectedWatch(page:String):Boolean = watch.containsMatchIn(page)
}
