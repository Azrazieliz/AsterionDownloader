package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class OpenWebModelSearchTest {
    @Test fun bingMeasuredOriginalFileIsDecodedBeforeCheckingGlbExtension(){
        val original="https://models.example.org/models/selected-sword.glb"
        val payload="a1"+java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(original.toByteArray(Charsets.UTF_8))
        val html="<li class='b_algo'><h2><a href='"+
            "https://www.bing.com/ck/a?u="+payload+
            "'>Sword original GLB</a></h2></li>"
        val models=OpenWebModelSearch.parseWebIndex(
            html,"https://www.bing.com/search?q=sword","Bing")
        assertEquals(1,models.size)
        assertEquals(original,models.single().url)
        assertEquals("GLB",models.single().format)
    }

    @Test fun webOriginalsCanComeFromAnyPublicHostWithoutRegisteringProviders(){
        val html="""<li class="b_algo"><h2><a
          href="https://artist.example.org/models/character.glb">Character model GLB</a></h2></li>
          <li class="b_algo"><h2><a
          href="https://cdn.other.example/assets/character.fbx">Character rigged FBX</a></h2></li>
          <li class="b_algo"><h2><a
          href="https://market.example.org/paid/character">Character paid store</a></h2></li>"""
        val results=OpenWebModelSearch.parseWebIndex(html,
            "https://www.bing.com/search?q=character","Bing")
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

    @Test fun federatedSearchAcceptsOnlyFreeDownloadableActualGlbFormats(){
        val data="""{"results":[
          {"id":"threedassets:bastard-sword","title":"Bastard Sword",
           "provider":"threedassets","downloadable":true,"price":{"free":true},
           "formats":["glb"],"thumbnailUrl":"https://cdn.3dassets.dev/sword.webp",
           "url":"https://3dassets.dev/assets/bastard-sword"},
          {"id":"store:paid-sword","title":"Paid Sword","downloadable":true,
           "price":{"free":false},"formats":["glb"]},
          {"id":"locked:broken-sword","title":"Broken Sword",
           "downloadable":false,"price":{"free":true},"formats":["glb"]},
          {"id":"other:sword-obj","title":"Sword OBJ",
           "downloadable":true,"price":{"free":true},"formats":["obj"]}
        ]}"""
        val found=OpenWebModelSearch.parseFederatedModels(data,"sword")
        assertEquals(1,found.size)
        assertTrue(found.single().url.startsWith(
            "https://3d.shep.bot/v1/assets/threedassets%3Abastard-sword/download"))
        assertEquals("GLB",found.single().format)
    }
    @Test fun grab3dUsesOnlyPublishedFullGlbNotPreviewOrMarketingPage(){
        val data="""{"models":[{
          "slug":"sword-prop","name":{"en":"Sword Prop"},
          "tags":["sword","medieval"],
          "media":{"thumbnail":"https://grab3d.com/m/sword.png"},
          "files":[
            {"format":"glb","variant":"light",
             "url":"https://grab3d.com/dl/sword-prop/sword-light.glb"},
            {"format":"glb","variant":"full",
             "url":"https://grab3d.com/dl/sword-prop/sword-full.glb"}
          ]
        }]}"""
        val found=OpenWebModelSearch.parseGrab3DModels(data,"sword")
        assertEquals(1,found.size)
        assertEquals("https://grab3d.com/dl/sword-prop/sword-full.glb",
            found.single().url)
    }
}

