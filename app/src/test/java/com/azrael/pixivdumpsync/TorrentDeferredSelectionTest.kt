package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class TorrentDeferredSelectionTest {
    private val id="a".repeat(24)
    private fun state()=mutableMapOf<String,Any>(
        "job_deferred_$id" to true,
        "job_folder_$id" to "Videos",
        "job_indices_$id" to "0,2,19",
        "job_paused_$id" to false,
        "job_cancelled_$id" to false
    )
    @Test fun processRestartPreservesOnlyExactChosenFileIndices(){
        assertEquals(listOf(id),TorrentDeferredSelection.recover(state()))
        assertFalse(TorrentDeferredSelection.recover(state().apply{
            put("job_indices_$id","")
        }).contains(id))
        assertFalse(TorrentDeferredSelection.recover(state().apply{
            put("job_indices_$id","0,-1")
        }).contains(id))
    }
    @Test fun pausedCancelledAndUnselectedJobsNeverAutoRestart(){
        assertTrue(TorrentDeferredSelection.recover(state().apply{
            put("job_paused_$id",true)
        }).isEmpty())
        assertTrue(TorrentDeferredSelection.recover(state().apply{
            put("job_cancelled_$id",true)
        }).isEmpty())
        assertTrue(TorrentDeferredSelection.recover(state().apply{
            remove("job_deferred_$id")
        }).isEmpty())
        assertTrue(TorrentDeferredSelection.recover(state().apply{
            put("job_folder_$id","Unapproved")
        }).isEmpty())
        assertTrue(TorrentDeferredSelection.recover(state().apply{
            put("job_indices_$id","1,20000")
        }).isEmpty())
    }
}
