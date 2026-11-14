package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VideoPayloadBytesTest {
    @Test fun journalNeverCountsSidecarBytesAsOriginalMedia(){
        assertFalse(VideoPayloadBytes.isPayload("native_original.mp4.etag"))
        assertFalse(VideoPayloadBytes.isPayload("video.mp4.ytdl"))
        assertFalse(VideoPayloadBytes.isPayload("metadata.json"))
        assertFalse(VideoPayloadBytes.isPayload("original.mp4.info.json"))
        assertTrue(VideoPayloadBytes.isPayload("native_original.mp4.part"))
        assertTrue(VideoPayloadBytes.isPayload("scene.f137.mp4.part"))
        assertTrue(VideoPayloadBytes.isPayload("original.mkv"))
        assertTrue(VideoPayloadBytes.isPayload("original.webm"))
    }
    @Test fun countsOnlyActualStagedOriginalBytes(){
        val dir=java.nio.file.Files.createTempDirectory("video-payload-").toFile()
        try{
            File(dir,"native_original.mp4.etag").writeBytes(ByteArray(200))
            File(dir,"metadata.json").writeBytes(ByteArray(300))
            File(dir,"native_original.mp4.part").writeBytes(ByteArray(1024))
            assertEquals(1024L,VideoPayloadBytes.count(dir))
        }finally{dir.deleteRecursively()}
    }
}
