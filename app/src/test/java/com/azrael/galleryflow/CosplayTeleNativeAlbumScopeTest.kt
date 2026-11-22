package com.azrael.galleryflow

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class CosplayTeleNativeAlbumScopeTest {
    @Test fun selectedArchiveLinkCannotBeReplacedByRelatedPostOrLookalikeHost(){
        val selected="https://cosplaytele.com/selected-cosplay-set/"
        val doc=Jsoup.parse("""
          <main><article id="post-43" class="post-43">
            <div class="entry-content">
              <div class="gallery galleryid-43">
                <a href="https://cdn.cosplaytele.com/set/full01.jpg">
                  <img src="https://cdn.cosplaytele.com/set/thumb01.jpg"/></a>
              </div>
              <a href="https://www.mediafire.com/file/selected-original.zip">
                Selected full archive</a>
              <a href="https://mediafire.com.attacker.example/file/spoof.zip">
                Spoof host</a>
              <div class="related"><a href="https://www.mediafire.com/file/other.zip">
                Another album</a></div>
            </div></article>
            <aside><a href="https://gofile.io/d/unrelated">Trending gallery</a></aside>
          </main>
        """.trimIndent(),selected)
        val scoped=SelectedAlbumScope.selectedArchiveArticle(doc,selected)
        val links=CosplayTeleAdapter(HttpClient()).providerLinks(scoped)
        assertEquals(1,links.size)
        assertEquals("https://www.mediafire.com/file/selected-original.zip",
            links.single().first)
        assertEquals("MediaFire",links.single().second)
        assertFalse(scoped.html().contains("other.zip"))
        assertFalse(scoped.html().contains("unrelated"))
    }

    @Test fun relatedOnlyArchiveDoesNotBecomeSelectedGalleryOriginal(){
        val selected="https://cosplaytele.com/selected-cosplay-set/"
        val doc=Jsoup.parse("""
          <main><article><div class="entry-content">
            <img src="https://cdn.cosplaytele.com/set/selected.jpg"/>
            <div class="related">
              <a href="https://www.mediafire.com/file/unrelated-archive.zip">
                Related gallery archive</a>
            </div>
          </div></article></main>
        """.trimIndent(),selected)
        val scoped=SelectedAlbumScope.selectedArchiveArticle(doc,selected)
        assertTrue(CosplayTeleAdapter(HttpClient()).providerLinks(scoped).isEmpty())
    }

    @Test fun officialSameSiteVideoIframeBesidePhotoGalleryIsPreserved(){
        val selectedFrame="https://cosplaytele.com/embed/video12345"
        val doc=Jsoup.parse("""
          <main><article id="post-999" class="post-999"><div class="entry-content">
            <div class="gallery galleryid-999">
              <a href="https://cdn.cosplaytele.com/selected/photo01.jpg">
                <img src="https://cdn.cosplaytele.com/selected/thumb01.jpg"/></a>
            </div>
            <iframe src="$selectedFrame"></iframe>
            <div class="related"><iframe
              src="https://cosplaytele.com/embed/foreign123"></iframe></div>
            <iframe src="https://ads.example/embed/malicious123"></iframe>
          </div></article></main>
        """,selected)
        val scoped=SelectedAlbumScope.selectedContent(doc,selected)
        assertEquals(1,scoped.select("iframe[src]").size)
        assertEquals(selectedFrame,scoped.selectFirst("iframe[src]")!!.absUrl("src"))
        val photos=directPageAssets(scoped,selected)
        assertEquals(1,photos.size)
        assertTrue(photos.single().url.contains("photo01.jpg"))
    }

    private val selected="https://cosplaytele.com/selected-cosplay-set/"

    @Test fun nativeDirectPageExtractorNeverIncludesRecommendedGalleryOrPageAssets(){
        val photos=(1..37).joinToString("") { n ->
            "<figure><a href='https://cdn.cosplaytele.com/set/$n.jpg'>"+
            "<img src='https://cdn.cosplaytele.com/set/thumb$n.jpg' /></a></figure>"
        }
        val other=(1..113).joinToString("") { n ->
            "<a href='https://cdn.cosplaytele.com/other/$n.jpg'>"+
            "<img src='https://cdn.cosplaytele.com/other/thumb$n.jpg' /></a>"
        }
        val html="<main><div class='recommendations'>$other</div>"+
            "<article id='post-123' class='post-123'>"+
            "<div class='entry-content'><h1>37 photos</h1>$photos"+
            "<div class='related'>$other</div></div></article></main>"
        val doc=Jsoup.parse(html,selected)
        val unscoped=directPageAssets(doc,selected).filter {
            it.kind==MediaKind.IMAGE
        }
        assertTrue(unscoped.size>37)
        val selectedContent=SelectedAlbumScope.selectedContent(doc,selected)
        val scoped=directPageAssets(selectedContent,selected).filter {
            it.kind==MediaKind.IMAGE
        }
        assertEquals(37,scoped.size)
        assertTrue(scoped.all{"/set/" in it.url})
        SelectedAlbumScope.verifyAdvertisedCount(37,scoped.size)
    }

    @Test fun wordpressPhotoGalleryDoesNotHideSiblingSelectedVideo(){
        val doc=Jsoup.parse("""
          <main><article id="post-123" class="post-123"><div class="entry-content">
            <div class="gallery galleryid-123">
              <a href="https://cdn.cosplaytele.com/set/1.jpg">
                <img src="https://cdn.cosplaytele.com/set/thumb.jpg"/></a>
            </div>
            <div class="video-player">
              <video src="https://cdn.cosplaytele.com/set/full.mp4" controls></video>
            </div>
            <a href="https://cdn.cosplaytele.com/set/alternate.webm">Video backup</a>
            <div class="related">
              <a href="https://cdn.cosplaytele.com/unrelated.mp4">Unrelated</a>
            </div>
          </div></article></main>
        """,selected)
        val content=SelectedAlbumScope.selectedContent(doc,selected)
        val assets=directPageAssets(content,selected)
        assertEquals(3,assets.size)
        assertTrue(assets.any{it.url.endsWith("/set/1.jpg")})
        assertTrue(assets.any{it.url.endsWith("/set/full.mp4")})
        assertTrue(assets.any{it.url.endsWith("/set/alternate.webm")})
        assertFalse(assets.any{it.url.contains("unrelated")})
    }

    @Test fun aVideoOnlySelectedAlbumCanReachTheOriginalResolver(){
        val doc=Jsoup.parse("""
          <main><article><div class="entry-content">
            <div class="video-player"><iframe data-video="selected"
              src="https://player.example/embed/selected"></iframe></div>
          </div></article></main>
        """,selected)
        val isolated=SelectedAlbumScope.selectedContent(doc,selected)
        assertEquals(1,isolated.select(".video-player iframe[data-video]").size)
        val planned=planCosplayTeleAlbumMedia("video-only",selected,
            directPageAssets(isolated,selected),null,null,1)
        assertTrue(planned.isEmpty())
        assertTrue(CosplayTeleVideoCompleteness.queueVideoFallback(
            true,CosplayTeleVideoCompleteness.Audit(1,0,false),false))
    }

    @Test fun selectedVideoDownloadLinksAreNotDiscardedAsNonImageAnchors(){
        val doc=Jsoup.parse("""
          <main><article><div class="entry-content">
            <h2>Selected cosplay 2 videos</h2>
            <a href="https://cdn.cosplaytele.com/set/full-scene.mp4">Full video</a>
            <a href="https://cosplaytele.com/unrelated-set/">Related set</a>
            <a href="https://cdn.cosplaytele.com/set/highres.jpg"><img src="/thumb.jpg"/></a>
          </div></article></main>
        """,selected)
        val scoped=SelectedAlbumScope.selectedContent(doc,selected)
        val assets=directPageAssets(scoped,selected)
        assertEquals(2,assets.size)
        assertTrue(assets.any{it.kind==MediaKind.VIDEO &&
            it.url.endsWith("/set/full-scene.mp4")})
        assertFalse(scoped.select("a[href*=unrelated-set]").isNotEmpty())
    }

    @Test fun selectedArticleDirectVideoIsRetainedButUnrelatedVideosAreExcluded(){
        val doc=Jsoup.parse("""
          <main><div class="recommended">
          <video src="https://cdn.cosplaytele.com/other.mp4"></video></div>
          <article><div class="post-content">
          <img src="https://cdn.cosplaytele.com/selected/1.jpg"/>
          <video controls src="https://cdn.cosplaytele.com/selected/full.mp4"></video>
          <aside class="related">
            <img src="https://cdn.cosplaytele.com/foreign.jpg"/>
          </aside>
          </div></article></main>
        """,selected)
        val content=SelectedAlbumScope.selectedContent(doc,selected)
        val direct=directPageAssets(content,selected)
        assertEquals(2,direct.size)
        assertTrue(direct.any{it.kind==MediaKind.IMAGE &&
            it.url.endsWith("/selected/1.jpg")})
        assertTrue(direct.any{it.kind==MediaKind.VIDEO &&
            it.url.endsWith("/selected/full.mp4")})
        assertTrue(direct.none{"/other.mp4" in it.url ||
            "/foreign.jpg" in it.url})
    }
}
