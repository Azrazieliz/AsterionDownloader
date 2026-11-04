package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class ContentVerticalsTest {
    @Test fun allApprovedSystemsAreOneExistingAppContentRegistry(){
        assertEquals(6,ContentVerticalsPanel.sections.size)
        assertEquals(6,ContentVerticalsPanel.sections.map{it.id}.toSet().size)
        assertEquals(setOf("STUDIO","MMD","LIVE2D","ANIMATION","MODS","VOICE"),
            ContentVerticalsPanel.sections.map{it.area}.toSet())
    }
    @Test fun characterCreationIsLimitedToKoikatsuAfterParty(){
        val entry=ContentVerticalsPanel.sections.single{it.id=="STUDIO"}
        assertTrue(entry.title.contains("Koikatsu After Party"))
        assertFalse(entry.subtitle.contains("Honey Select"))
        assertFalse(entry.subtitle.contains("AI Shoujo"))
    }
    @Test fun immersiveOrHeadsetOnlyContentIsAbsent(){
        assertFalse(ContentVerticalsPanel.sections.any{
            it.id=="VR" || it.title.contains("VR",ignoreCase=true) ||
                it.subtitle.contains("headset",ignoreCase=true)
        })
    }
    @Test fun approvedSystemsReuseExistingSourceIndex(){
        assertTrue(ContentVerticalsPanel.sections.all{item->
            item.sources.all{SourceIndexParser.safe(it.url)}
        })
    }
}
