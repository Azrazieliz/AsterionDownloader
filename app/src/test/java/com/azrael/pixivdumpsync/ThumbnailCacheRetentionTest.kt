package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ThumbnailCacheRetentionTest {
    @Test fun prunesOnlyOldHashedThumbnailFilesWithoutTouchingActiveOrUserFiles() {
        val root=Files.createTempDirectory("asterion-preview-cap-test-").toFile()
        try {
            val now=System.currentTimeMillis()
            fun thumbnail(letter:Char,ageMs:Long):File {
                val f=File(root,letter.toString().repeat(64))
                f.writeBytes(ByteArray(20){1})
                f.setLastModified(now-ageMs)
                return f
            }
            val oldest=thumbnail('a',600_000)
            val middle=thumbnail('b',300_000)
            val active=thumbnail('c',1000)
            val original=File(root,"owner-saved-original.mp4").apply{
                writeBytes(ByteArray(80){2});setLastModified(now-1_000_000)
            }
            val count=ThumbnailCacheRetention.prune(root,active,
                maxBytes=22L,maxEntries=1,now=now)
            assertEquals(2,count)
            assertFalse(oldest.exists())
            assertFalse(middle.exists())
            assertTrue(active.exists())
            assertTrue(original.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun neverDeletesActiveFileEvenIfOlderThanCacheEntries() {
        val root=Files.createTempDirectory("asterion-protected-preview-").toFile()
        try {
            val now=System.currentTimeMillis()
            val active=File(root,"a".repeat(64)).apply {
                writeBytes(ByteArray(32));setLastModified(now-400_000)
            }
            val other=File(root,"b".repeat(64)).apply {
                writeBytes(ByteArray(32));setLastModified(now-380_000)
            }
            ThumbnailCacheRetention.prune(root,active,32,1,now)
            assertTrue(active.exists())
            assertFalse(other.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun rejectsOversizedThumbnailWithoutWritingTheOverflowChunk() {
        val input=java.io.ByteArrayInputStream(ByteArray(35){7})
        val output=java.io.ByteArrayOutputStream()
        try {
            ThumbnailPayloadLimit.copy(input,output,24L)
            fail("Oversized preview should not be cached")
        } catch(expected:java.io.IOException) {
            assertTrue(expected.message.orEmpty().contains("limit"))
        }
        assertTrue(output.size()<=24)
    }

    @Test fun acceptsPreviewExactlyAtLimit() {
        val input=java.io.ByteArrayInputStream(ByteArray(24){9})
        val output=java.io.ByteArrayOutputStream()
        assertEquals(24L,ThumbnailPayloadLimit.copy(input,output,24L))
        assertArrayEquals(ByteArray(24){9},output.toByteArray())
    }

}
