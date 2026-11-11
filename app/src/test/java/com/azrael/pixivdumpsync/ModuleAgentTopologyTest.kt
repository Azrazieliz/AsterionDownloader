package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

/** Configuration regression: the same two agent lanes are reused by
 * automatic and manually requested originals in each source module.
 * Device acceptance must separately confirm simultaneous saved receipts.
 */
class ModuleAgentTopologyTest {
    @Test fun exactlyTwoWorkersPerModule(){
        assertEquals(2,ModuleOriginalAgents.AGENTS_PER_MODULE)
        assertTrue(ModuleOriginalAgents.MODULES.size>=16)
    }
    @Test fun moduleTransferLimitsHaveIndependentDestinations(){
        val folders=ModuleOriginalAgents.MODULES.map(ModuleOriginalAgents::folder)
        assertEquals("Two modules share the same DownloadManager quota",folders.size,
            folders.toSet().size)
    }
    @Test fun everyRegisteredDirectOriginalModuleHasDedicatedPair(){
        val areas=listOf(
            "ADULT","HENTAI","VIDEOS","CREATORS","COSPLAY","ARTWORK",
            "BOOKS","COMICS","GAMES","ASSETS","STUDIO","MMD",
            "LIVE2D","ANIMATION","MODS","VOICE","PIXIV","X"
        )
        for(area in areas) assertTrue(
            "No two-agent module lane for "+area,
            ModuleOriginalAgents.MODULES.contains(area))
    }
}
