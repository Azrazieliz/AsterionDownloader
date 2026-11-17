package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class ArchivebateListingPresenceTest {
    @Test fun acceptsObservedCatalogRoutesOnly() {
        assertTrue(ArchivebateListingPresence.hasSelectedWatch(
            """<a href="/u/watch/16458050">selected</a>"""))
        assertTrue(ArchivebateListingPresence.hasSelectedWatch(
            """<a href="/watch/16458050">selected</a>"""))
        assertTrue(ArchivebateListingPresence.hasSelectedWatch(
            """<a href="https://archivebate.com/u/watch/16458050/">selected</a>"""))
        assertTrue(ArchivebateListingPresence.hasSelectedWatch(
            """<a href="https://www.archivebate.com/watch/16458050">selected</a>"""))
    }
    @Test fun rejectsLegalPagesUnrelatedHostsAndBlankCatalogs() {
        for(html in listOf(
            """<a href="/2257">Legal</a>""",
            """<a href="/u/watch/not-numeric">Gallery</a>""",
            """<a href="https://fakearchivebate.com/u/watch/16458050">Ad</a>""",
            """<a href="javascript:alert(1)">Continue</a>""",
            """<div>0 videos available</div>"""
        ))assertFalse(html,ArchivebateListingPresence.hasSelectedWatch(html))
    }
}
