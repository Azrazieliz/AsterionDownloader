package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VideoStagingTest {
    @Test fun migratesLegacyPartsWithoutErasingOriginal() {
        val root=Files.createTempDirectory("asterion-stage").toFile()
        try {
            val legacy=File(root,"cache").apply{mkdirs()}
            val persistent=File(root,"files")
            val bytes=ByteArray(4096){(it%251).toByte()}
            File(legacy,"scene.mp4.part").writeBytes(bytes)
            File(legacy,"scene.mp4.ytdl").writeText("resume state")
            val dest=VideoStaging.prepare(legacy,persistent)
            assertEquals(persistent.canonicalFile,dest.canonicalFile)
            assertArrayEquals(bytes,File(dest,"scene.mp4.part").readBytes())
            assertEquals("resume state",File(dest,"scene.mp4.ytdl").readText())
            assertArrayEquals(bytes,File(legacy,"scene.mp4.part").readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun neverOverwritesLongerDurablePartial() {
        val root=Files.createTempDirectory("asterion-stage").toFile()
        try {
            val legacy=File(root,"cache").apply{mkdirs()}
            val persistent=File(root,"files").apply{mkdirs()}
            File(legacy,"scene.webm.part").writeBytes(ByteArray(16){1})
            File(persistent,"scene.webm.part").writeBytes(ByteArray(32){2})
            VideoStaging.prepare(legacy,persistent)
            assertEquals(32L,File(persistent,"scene.webm.part").length())
            assertEquals(2.toByte(),File(persistent,"scene.webm.part").readBytes()[0])
        } finally { root.deleteRecursively() }
    }

    @Test fun missingLegacyCacheIsFine() {
        val root=Files.createTempDirectory("asterion-stage").toFile()
        try {
            val destination=File(root,"durable")
            assertEquals(destination,VideoStaging.prepare(File(root,"absent"),destination))
            assertTrue(destination.isDirectory)
        } finally { root.deleteRecursively() }
    }
}
