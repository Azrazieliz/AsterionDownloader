package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class OpenWebModelPageDiscoveryTest {
    @Test fun yahooMeasuredOrganicLinkResolvesOnlyPublisherDetailPage(){
        val actual="https://creator.example.org/free-sword-model"
        val redirect="https://r.search.yahoo.com/_ylt=metadata/RU="+
            java.net.URLEncoder.encode(actual,"UTF-8")+"/RK=2/RS=metadata"
        val html="<div class='algo-sr'><div class='compTitle'><h3>"+
            "<a href='"+redirect+"'>Free medieval sword 3D model</a>"+
            "</h3></div></div>"
        val pages=OpenWebModelPageDiscovery.landingLinks(
            html,"https://search.yahoo.com/search?p=sword","Yahoo","sword")
        assertEquals(listOf(actual),pages)
    }

    @Test fun indexedSwordModelPageRevealsActualFilesNotAnotherPaidPage(){
        val results="""<div class="algo-sr"><div class="compTitle"><h3><a
            href="https://creator.example.org/free-medieval-sword-3d-model">
            Free medieval sword 3D model</a></h3></div></div>"""
        val urls=OpenWebModelPageDiscovery.landingLinks(results,
            "https://search.yahoo.com/search?p=sword","Yahoo","sword")
        assertEquals(listOf("https://creator.example.org/free-medieval-sword-3d-model"),urls)
        val page="""<html><title>Free Medieval Sword 3D Model</title>
            <body><main><h1>Medieval Sword 3D Model</h1>
            <a href="/models/sword.glb" download>Download GLB</a>
            <a href="/models/sword.fbx">Download FBX</a>
            <a href="https://paid.example.org/store/sword">Buy</a>
            <a href="http://creator.example.org/models/sword.obj">Unsafe HTTP</a>
            </main></body></html>"""
        val models=OpenWebModelPageDiscovery.originals(page,urls.single(),"sword")
        assertEquals(2,models.size)
        assertEquals(setOf("GLB","FBX"),models.map{it.format}.toSet())
        assertTrue(models.all{it.url.startsWith("https://creator.example.org/models/")})
    }

    @Test fun indexedModelRetainsOnlyItsOwnPublishedPoster(){
        val detail="https://models.example.org/free-sword"
        val html="""<html><head>
          <meta property="og:image" content="/preview/sword.webp"/>
          </head><body><main><h1>Free sword 3D model</h1>
          <a href="/objects/sword.vrm" download>Original VRM</a>
          <a href="/objects/sword.pmx" download>Original PMX</a>
          </main></body></html>"""
        val models=OpenWebModelPageDiscovery.originals(html,detail,"sword")
        assertEquals(setOf("VRM","PMX"),models.map{it.format}.toSet())
        assertTrue(models.all{
            it.thumbnail=="https://models.example.org/preview/sword.webp"
        })
    }

    @Test fun unrelatedDetailPagesAndHtmlLinksAreNotDownloaded(){
        val body="""<html><h1>Car model</h1><main>
            <a href="/sword.obj">Unrelated sword file</a></main></html>"""
        assertTrue(OpenWebModelPageDiscovery.originals(
            body,"https://assets.example.org/car","sword").isEmpty())
        val page="""<html><h1>Sword 3D model</h1><main>
            <a href="/download/sword">HTML download page</a>
            <a href="https://github.com/artist/swords/blob/main/models/sword.obj">
              Public OBJ in GitHub</a></main></html>"""
        val files=OpenWebModelPageDiscovery.originals(page,
            "https://assets.example.org/sword","sword")
        assertEquals(1,files.size)
        assertEquals("https://raw.githubusercontent.com/artist/swords/main/models/sword.obj",
            files.single().url)
    }
}
