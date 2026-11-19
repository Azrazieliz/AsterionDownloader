package com.azrael.pixivdumpsync

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MangaDexChaptersTest {
    private val manga="12345678-abcd-4567-89ab-123456789abc"
    private val first="abcdef12-3456-789a-bcde-abcdef123456"
    private val second="abcdef12-3456-789a-bcde-abcdef123457"

    @Test fun selectsOnlyExactPublisherTitlesAndChapters() {
        assertTrue(MangaDexChapters.isSeries("https://mangadex.org/title/$manga/sample-title"))
        assertTrue(MangaDexChapters.isReader("https://mangadex.org/chapter/$first"))
        assertFalse(MangaDexChapters.isSeries("https://mangadex.org/title/"))
        assertFalse(MangaDexChapters.isSeries(
            "https://mangadex.org.evil.example/title/$manga"))
        assertFalse(MangaDexChapters.isReader("https://mangadex.org/title/$manga"))
        assertFalse(MangaDexChapters.isSeries("http://mangadex.org/title/$manga"))
        assertFalse(MangaDexChapters.isReader(
            "https://mangadex.org/chapter/$first?other=item"))
    }

    @Test fun apiFeedPreservesEnglishOnlyAndExcludesExternalChapters() {
        val feed=JSONObject("""{
            "result":"ok","total":4,"data":[
              {"id":"$first","attributes":{"chapter":"1","volume":"1",
                "title":"English opening","translatedLanguage":"en"}},
              {"id":"$second","attributes":{"chapter":"2","volume":"1",
                "title":"Official continuation","translatedLanguage":"en"}},
              {"id":"cdefab12-3456-789a-bcde-abcdef123458",
                "attributes":{"chapter":"3","translatedLanguage":"fr"}},
              {"id":"cdefab12-3456-789a-bcde-abcdef123459",
                "attributes":{"chapter":"4","translatedLanguage":"en",
                  "externalUrl":"https://unknown.example/other"}}]
        }""")
        val chapters=MangaDexChapters.parseFeed(feed)
        assertEquals(2,chapters.size)
        assertEquals("https://mangadex.org/chapter/$first",chapters[0].url)
        assertEquals("https://mangadex.org/chapter/$second",chapters[1].url)
        assertTrue(chapters[0].title.contains("Chapter 1"))
        assertTrue(chapters[1].title.contains("Chapter 2"))
    }

    @Test fun manifestUsesFullOriginalsInPublishedOrderNotDataSaver() {
        val body=JSONObject("""{
            "result":"ok",
            "baseUrl":"https://cmdxd98sb0x3yprd.mangadex.network",
            "chapter":{
                "hash":"1234567890abcdef1234567890abcdef",
                "data":["001.jpg","002.png","003.webp"],
                "dataSaver":["001-small.jpg","002-small.png","003-small.webp"]
            }
        }""")
        val originals=MangaDexChapters.parseAtHome(body)
        assertEquals(3,originals.size)
        assertEquals(
            "https://cmdxd98sb0x3yprd.mangadex.network/data/1234567890abcdef1234567890abcdef/001.jpg",
            originals[0])
        assertTrue(originals[2].endsWith("/003.webp"))
        assertFalse(originals.any{it.contains("small")})
    }

    @Test fun rejectAdvertisementsAndUnsafePageIdentifiers() {
        val base="""{"result":"ok","baseUrl":"https://evil.example",
            "chapter":{"hash":"1234567890abcdef1234567890abcdef",
            "data":["001.jpg"]}}"""
        assertThrows(IllegalArgumentException::class.java){
            MangaDexChapters.parseAtHome(JSONObject(base))
        }
        val injected="""{"result":"ok",
            "baseUrl":"https://uploads.mangadex.org",
            "chapter":{"hash":"1234567890abcdef1234567890abcdef",
            "data":["../../ad.jpg"]}}"""
        assertThrows(IllegalArgumentException::class.java){
            MangaDexChapters.parseAtHome(JSONObject(injected))
        }
    }
}
