package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

/** The JSON shape is read from the actual pinned bundled 2026.10.26
 * yt_dlp/downloader/fragment.py (not guessed from UI percentage).
 */
class VideoHlsFragmentProgressTest {
    private val metadata="""{"downloader":{"current_fragment":{"index":72},"fragment_count":144}}"""
    @Test fun exactCompletedFragmentsProduceBoundedNonFinalProgress(){
        val sample=VideoHlsFragmentProgress.parse(metadata)
        assertNotNull(sample)
        assertEquals(72,sample!!.completed)
        assertEquals(144,sample.total)
        assertEquals(47,sample.percent)
        assertEquals("72/144 fragments",sample.description)
        assertTrue(VideoHlsFragmentProgress.parse(
            """{"downloader":{"current_fragment":{"index":144},"fragment_count":144}}"""
        )!!.percent<100)
    }
    @Test fun actualMediaPartAndSingleRealCheckpointRequired(){
        val folder=Files.createTempDirectory("asterion-hls-progress").toFile()
        try {
            val state=File(folder,"selected.mp4.part.ytdl")
            state.writeText(metadata)
            assertNull(VideoHlsFragmentProgress.inspect(folder))
            File(folder,"selected.mp4.part").writeBytes(ByteArray(4096))
            assertEquals(72,VideoHlsFragmentProgress.inspect(folder)!!.completed)
            File(folder,"another.mp4.part.ytdl").writeText(
                """{"downloader":{"current_fragment":{"index":22},"fragment_count":144}}""")
            assertEquals("Progress cannot exceed the slowest verified track",
                22,VideoHlsFragmentProgress.inspect(folder)!!.completed)
            File(folder,"another.mp4.part.ytdl").writeText(
                """{"downloader":{"fragment_count":"corrupt"}}""")
            assertNull("Corrupt track cannot create false completion",
                VideoHlsFragmentProgress.inspect(folder))
        }finally{folder.deleteRecursively()}
    }
    @Test fun neverTreatCorruptOrUnknownCheckpointAsCompletion(){
        for(json in listOf(
            """{"downloader":{"current_fragment":{"index":0},"fragment_count":144}}""",
            """{"downloader":{"current_fragment":{"index":145},"fragment_count":144}}""",
            """{"downloader":{"current_fragment":{"index":100}}}""",
            """{"downloader":{"current_fragment":{"index":1},"fragment_count":0}}""",
            """{"downloader":{"current_fragment":{"index":1},"fragment_count":10000000}}""",
            """{"downloader":{"current_fragment":{"index":"unknown"},"fragment_count":144}}""",
            "not json"))
            assertNull(json,VideoHlsFragmentProgress.parse(json))
    }
}
