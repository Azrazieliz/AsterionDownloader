package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class PornBuzSelectedOriginalRoutingTest {
    private val selected="https://pornbuz.com/video/example-valid-scene-123456/"

    @Test fun selectedServerTwoIsOnlyOriginalCandidate() {
        assertTrue(PornBuzOriginalSource.owns(selected))
        val page="""<main>
          <video src="/preview/selected-20s.mp4"></video>
          <a href="https://streamtape.com/v/ABC123def45/original-video">Server 2</a>
          <aside class="related"><a href="https://streamtape.com/v/ZXY987def45/other-video">Server 2</a></aside>
          <a href="https://example.invalid/random.mp4">Server 3</a>
        </main>"""
        assertEquals(listOf("https://streamtape.com/v/ABC123def45/original-video"),
            PornBuzOriginalSource.published(selected,page))
    }

    @Test fun absentOrAmbiguousServerTwoMustNeverSubstituteTeaser() {
        val adOnly="""<main><video src="/preview/selected-20s.mp4"></video></main>"""
        assertTrue(PornBuzOriginalSource.published(selected,adOnly).isEmpty())
        val ambiguous="""<main>
           <a href="https://streamtape.com/v/ABC123def45/one">Server 2</a>
           <a href="https://streamtape.com/v/XYZ123def45/two">Server 2</a>
           </main>"""
        assertTrue(PornBuzOriginalSource.published(selected,ambiguous).isEmpty())
        assertTrue(UnverifiedSelectedPreviewGuard.shouldHold(
            selected,20_000L,null))
    }
}
