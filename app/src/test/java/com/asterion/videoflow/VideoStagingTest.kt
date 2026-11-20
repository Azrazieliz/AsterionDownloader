package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VideoStagingTest {
    @Test fun migratesLegacyPartsVerifiesDurableCopyAndRemovesRedundantCache() {
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
            assertFalse("Exact duplicated old cache bytes must be reclaimed",
                File(legacy,"scene.mp4.part").exists())
            assertFalse("Duplicate yt-dlp sidecars may also be reclaimed",
                File(legacy,"scene.mp4.ytdl").exists())
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

    @Test fun differentDurableDataIsNeverReplacedOrDiscarded() {
        val root=Files.createTempDirectory("asterion-stage-different-").toFile()
        try {
            val legacy=File(root,"cache").apply{mkdirs()}
            val persistent=File(root,"durable").apply{mkdirs()}
            val old=File(legacy,"movie.mp4.part").apply{
                writeBytes(ByteArray(32){1})
            }
            val now=File(persistent,"movie.mp4.part").apply{
                writeBytes(ByteArray(32){2})
            }
            VideoStaging.prepare(legacy,persistent)
            assertTrue("Only unique historical partial must be preserved",old.exists())
            assertEquals(2.toByte(),now.readBytes()[0])
        }finally{root.deleteRecursively()}
    }

    @Test fun cancelDeletesOnlyExactJobStageNeverPausedOrSavedOrSibling(){
        val root=Files.createTempDirectory("asterion-cancel-stage-").toFile()
        try{
            val staging=File(root,"staging").apply{mkdirs()}
            val cancelled=File(staging,"42").apply{mkdirs()}
            File(cancelled,"movie.mp4.part").writeBytes(ByteArray(800))
            File(cancelled,"movie.m3u8").writeText("manifest")
            val paused=File(staging,"43").apply{mkdirs()}
            File(paused,"movie.mp4.part").writeBytes(ByteArray(600))
            val published=File(root,"owner-original.mp4").apply{
                writeBytes(ByteArray(2000))
            }
            val count=VideoStaging.discardCancelled(staging,42)
            assertTrue(count>=800L)
            assertFalse(cancelled.exists())
            assertTrue(File(paused,"movie.mp4.part").exists())
            assertEquals(2000L,published.length())
            assertEquals(0L,VideoStaging.discardCancelled(staging,0))
            assertEquals(0L,VideoStaging.discardCancelled(staging,42))
        }finally{root.deleteRecursively()}
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
