package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class PortableGameExporterTest {
    @Test fun rejectsTraversalAndAbsoluteEntries(){
        assertNull(PortableGameExporter.safeEntry("../evil.exe"))
        assertNull(PortableGameExporter.safeEntry("game/../../evil"))
        assertNull(PortableGameExporter.safeEntry("/root/external"))
        assertNull(PortableGameExporter.safeEntry("C:/Windows/file"))
        assertNull(PortableGameExporter.safeEntry("game\\evil.exe"))
        assertEquals(listOf("game","images","a.png"),
            PortableGameExporter.safeEntry("game/images/a.png"))
    }
    @Test fun recognizesPortableRuntimeTargets(){
        assertTrue(PortableGameExporter.classify(setOf("game/renpy/script.rpy"))
            .contains("Ren'Py"))
        assertTrue(PortableGameExporter.classify(setOf("game/data.xp3"))
            .contains("Kirikiri"))
        assertTrue(PortableGameExporter.classify(setOf("game/index.html"))
            .contains("HTML5"))
        assertTrue(PortableGameExporter.classify(setOf("game/start.exe"))
            .contains("Windows"))
    }
}
