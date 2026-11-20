package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class OpenWebModelSearchTest {
    @Test fun webOriginalsCanComeFromAnyPublicHostWithoutRegisteringProviders(){
        val html="""<div class="result"><a class="result__a"
          href="https://artist.example.org/models/character.glb">Character model GLB</a></div>
          <div class="result"><a class="result__a"
          href="https://cdn.other.example/assets/character.fbx">Character rigged FBX</a></div>
          <div class="result"><a class="result__a"
          href="https://market.example.org/paid/character">Character paid store</a></div>"""
        val results=OpenWebModelSearch.parseWebIndex(html,
            "https://html.duckduckgo.com/html/","DuckDuckGo")
        assertEquals(2,results.size)
        assertEquals(setOf("GLB","FBX"),results.map{it.format}.toSet())
        assertTrue(results.none{it.host=="market.example.org"})
    }

    @Test fun fakeAssetsHttpAndPrivateUrlsAreRejected(){
        val html="""<li class="b_algo"><h2>
           <a href="https://models.example.org/character.glb">Character model</a>
           </h2></li><li class="b_algo"><h2>
           <a href="http://models.example.org/character.glb">HTTP model</a>
           </h2></li><li class="b_algo"><h2>
           <a href="https://127.0.0.1/character.fbx">Private model</a>
           </h2></li><li class="b_algo"><h2>
           <a href="https://models.example.org/character.svg">Fake image</a>
           </h2></li>"""
        val results=OpenWebModelSearch.parseWebIndex(html,
            "https://www.bing.com/search?q=character","Bing")
        assertEquals(1,results.size)
        assertEquals("https://models.example.org/character.glb",
            results.single().url)
    }
}
