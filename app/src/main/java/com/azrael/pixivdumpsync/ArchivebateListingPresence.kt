package com.azrael.pixivdumpsync

/** The only accepted first-party Archivebate watch links in a rendered list. */
internal object ArchivebateListingPresence {
    private val watch=Regex(
        """(?i)\bhref\s*=\s*["'](?:https://(?:www\.)?archivebate\.com)?/(?:u/)?watch/[0-9]{1,16}/?(?:[?#][^"'<>]*)?["']"""
    )
    fun hasSelectedWatch(page:String):Boolean = watch.containsMatchIn(page)
}
