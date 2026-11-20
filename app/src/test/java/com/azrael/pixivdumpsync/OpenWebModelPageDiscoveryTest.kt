package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class OpenWebModelPageDiscoveryTest {
    @Test fun indexedSwordModelPageRevealsActualFilesNotAnotherPaidPage(){
        val results="""<div class="result"><a class="result__a"
            href="https://creator.example.org/free-medieval-sword-3d-model">
            Free medieval sword 3D model</a></div>"""
        val urls=OpenWebModelPageDiscovery.landingLinks(results,
            "https://html.duckduckgo.com/html/","DuckDuckGo","sword")
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
