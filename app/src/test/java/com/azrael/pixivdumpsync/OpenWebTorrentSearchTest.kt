package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

/** Open-internet results are candidate descriptors, never HTML/advertising
 * pages or proof that a torrent file actually exists. Existing native
 * descriptor validation and user-selected file chooser are unchanged. */
class OpenWebTorrentSearchTest {
    @Test fun exactPublicTorrentDescriptorsAreDiscoverableAcrossWebIndexes(){
        val page="""<div class="result">
        <a class="result__a" href="https://files.example.org/releases/scene.torrent">Scene Collection</a>
        </div><div class="result">
        <a class="result__a" href="https://files.example.org/releases/scene.torrent?utm_source=x">Scene Collection</a>
        </div><div class="result">
        <a class="result__a" href="https://files.example.org/releases/landing.html">Scene Collection</a>
        </div><div class="result">
        <a class="result__a" href="https://files.example.org/releases/another.torrent">Another Collection</a>
        </div>"""
        val hits=MultiTorrentSearch.webTorrentResults(page,
            "https://html.duckduckgo.com/html/","DuckDuckGo")
        assertEquals(2,hits.size)
        assertTrue(hits.all{it.kind==CatalogKind.TORRENT})
        assertTrue(hits.all{it.url.endsWith(".torrent")})
        assertTrue(hits.all{it.source=="Web · DuckDuckGo"})
    }

    @Test fun indexPagesAdsAndUntrustedSchemesNeverMasqueradeAsTorrentFiles(){
        val page="""<li class="b_algo"><h2>
        <a href="https://source.example.net/x.torrent">Verified title</a>
        </h2></li><li class="b_algo"><h2>
        <a href="http://source.example.net/x.torrent">HTTP only</a>
        </h2></li><li class="b_algo"><h2>
        <a href="https://127.0.0.1/private.torrent">Private network</a>
        </h2></li><li class="b_algo"><h2>
        <a href="https://source.example.net/watch/original">HTML listing</a>
        </h2></li><li class="b_algo"><h2>
        <a href="https://source.example.net/installer.exe">Installer</a>
        </h2></li>"""
        val hits=MultiTorrentSearch.webTorrentResults(page,
            "https://www.bing.com/search?q=title","Bing")
        assertEquals(1,hits.size)
        assertEquals("https://source.example.net/x.torrent",hits.single().url)
    }
}
