package com.asterion.videoflow

import org.junit.Assert.assertEquals
import org.junit.Test

/** Freeze the restored universal source-resolution allowance: JAVIDEO must
 * not silently receive a 35-second exception again.
 */
class JavideoPublisherResolveBudgetTest {
    @Test fun defaultFirstPartyPlayerMustNotSuppressSelectedStapeBrowser(){
        val selected="https://javideo.net/en/fc2ppv-4994603"
        val default=SiteSpecificOriginalSources.Source(
            "https://play.javideo.net/embed/SelectedDefault123",selected)
        val selectedStape=SiteSpecificOriginalSources.Source(
            "https://streamtape.com/e/SelectedMovie123",selected)
        assertEquals(true,SiteSpecificOriginalSources.javideoNeedsRenderedStape(
            selected,listOf(default)))
        assertEquals(true,SiteSpecificOriginalSources.javideoNeedsRenderedStape(
            selected,emptyList()))
        assertEquals(false,SiteSpecificOriginalSources.javideoNeedsRenderedStape(
            selected,listOf(selectedStape)))
        assertEquals(false,SiteSpecificOriginalSources.javideoNeedsRenderedStape(
            "https://javideo.net/en/",listOf(default)))
    }

    @Test fun originalSelectedPlayerHasSameDeadlineAsOtherProviders() {
        assertEquals(80_000L, VideoTransferLimits.NO_BYTES_RESOLVE_MS)
    }
}
