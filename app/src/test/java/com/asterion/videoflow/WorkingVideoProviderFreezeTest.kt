package com.asterion.videoflow

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Owner-reported working provider freeze at v1.3.72.
 * Dedicated provider source SHA-1 Git blobs are pinned, while shared adapters
 * are protected by selected-content regression tests. Repair failing providers
 * without silently rewriting successful ones. Update baseline only after
 * an explicitly verified regression and fresh phone acceptance.
 */
class WorkingVideoProviderFreezeTest {
    private val frozen = linkedMapOf(
        "FullPornerOriginalSource.kt" to "312878511c2b3fbb2be47e571d6cba5de8fbcd67",
        "FullPornerParallelOriginal.kt" to "598c7392829a599093b0bd20b1a46ed63051446b",
        "ThreeDHentaiSignedOriginal.kt" to "9bff43bbdf5d29479e4ac41636b21a2c3b159fa7",
        "Rule34VideoOriginalSource.kt" to "02d00276a554a92c685eef249656f9bcaf3e846f",
        "EpornerOriginalSource.kt" to "c9c4b21720bfbd702d48dea5be6a11dab38bc485",
        "FreePornVideosOriginalSource.kt" to "184e349e1b97800746578f79aa2bc1c9250fdeaa"
    )
    private fun sourceFile(name:String):File {
        val cwd=File(System.getProperty("user.dir") ?: ".")
        val module=if(File(cwd,"src/main/java").isDirectory)cwd
            else File(cwd,"app")
        return File(module,"src/main/java/com/asterion/videoflow/$name")
    }
    private fun gitBlobSha(bytes:ByteArray):String{
        val digest=MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size}\u0000".toByteArray(StandardCharsets.US_ASCII))
        digest.update(bytes)
        return digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    @Test fun dedicatedWorkingProviderEnginesRemainFrozen(){
        assertEquals("Freeze coverage must not silently disappear",6,frozen.size)
        frozen.forEach{(name,sha)->
            val source=sourceFile(name)
            assertTrue("Missing frozen provider source: "+name,source.isFile)
            // Git on the owner's Windows checkout may convert LF to CRLF.
            // Hash canonical repository bytes, not platform-specific checkout
            // line endings; substantive source changes still fail the freeze.
            val canonical=source.readText(Charsets.UTF_8)
                .replace("\r\n","\n").toByteArray(Charsets.UTF_8)
            assertEquals(
                "Working provider changed without revalidating its regression freeze: "+name,
                sha,gitBlobSha(canonical))
        }
    }
    @Test fun hentaibrosSelectedFlowplayerStillFindsOriginalNotRelatedVideo(){
        val selected="https://hentaibros.net/deco-x-deco-the-animation-episode-1/"
        val html="""<article class="post"><div class="video-player">
            <div id="wpfp_3235" data-item='{"sources":[
              {"src":"https://cdn.example/Selected Episode 01.mp4","type":"video/mp4"}]}'></div>
            </div><aside class="related"><div id="wpfp_9999"
              data-item='{"sources":[{"src":"https://cdn.example/other.mp4"}]}'></div>
            </aside></article>"""
        val found=SiteSpecificOriginalSources.selectedStatic(
            Jsoup.parse(html,selected),selected,"hentaibros.net")
        assertEquals(listOf("https://cdn.example/Selected%20Episode%2001.mp4"),
            found.map{it.url})
        assertEquals(selected,found.single().referer)
        assertTrue(NativeMediaDownloader.isNativeEligible(selected,found.single().url))
    }
    @Test fun rule34SelectedDownloadNeverRegressesToRelatedClip(){
        val selected="https://rule34video.com/video/4727779/female-v-x-bbc-reverse-cowgirl/"
        val html="""<main><div class="download-options">
            <a href="/get_file/2026/1/quality_720.mp4?download=true">
              MP4 720p3 MB</a></div>
            <aside class="related"><a href="/get_file/2026/1/ad.mp4?download=true">
              MP4 1080p</a></aside>
            </main>"""
        val originals=Rule34VideoOriginalSource.parse(html,selected)
        assertEquals(1,originals.size)
        assertTrue(originals.single().url.contains("quality_720.mp4"))
        assertTrue(NativeMediaDownloader.isNativeEligible(
            selected,originals.single().url,true))
        assertFalse(originals.any{it.url.contains("ad.mp4")})
        assertTrue(VideoSiteProfiles.isVideoPage(selected))
    }
}
