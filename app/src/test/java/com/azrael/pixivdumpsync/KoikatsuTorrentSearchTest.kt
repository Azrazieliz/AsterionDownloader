package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class KoikatsuTorrentSearchTest {
    @Test fun parseBepisCard(){
        val json = "{\"data\":{\"cards\":[{\"cardType\":\"KK\",\"id\":401111,\"cardData\":{\"firstName\":\"Kurumi\",\"lastName\":\"Haraga\"}}]}}"
        val cards=KoikatsuCards.parseBepis(json)
        assertEquals(1,cards.size)
        assertEquals("Kurumi Haraga",cards[0].name)
        assertEquals("https://db.bepis.moe/card/full/KK_401111.png",cards[0].download)
    }
    @Test fun parseKoikatsuCardsLinks(){
        val base="https://koikatsucards.com/search"
        val list=KoikatsuCards.parseArchive("<a class='card' href='/contents/demo'>Demo card</a>",base)
        assertEquals(1,list.size)
        val actual=KoikatsuCards.parseArchiveDownload("<div class='download-menu-panel'><a class='download-menu-item' href='/api/downloads/content-file/token/demo'>Get</a></div>",base)
        assertEquals("https://koikatsucards.com/api/downloads/content-file/token/demo",actual)
    }
    @Test fun gameTorrentMustContainIdentifiableGamePayload(){
        assertFalse(GameTorrentPolicy.hasGamePayload(listOf(
            "Movie 1080p.mkv", "preview/clip.mp4", "Extras/trailer.zip")))
        assertTrue(GameTorrentPolicy.hasGamePayload(listOf(
            "Game/game.exe", "Game/Assets/cutscene.mp4", "Game/data.rpa")))
        assertTrue(GameTorrentPolicy.hasGamePayload(listOf("Game Release.7z")))
        assertFalse(GameTorrentPolicy.hasGamePayload(listOf("Game video.mp4")))
    }
    @Test fun clearlyWrongMediaNeverQualifiesAsGame() {
        assertTrue(GameTorrentPolicy.clearlyNonGameTitle("Video collection 1080p WEB-DL x264"))
        assertTrue(GameTorrentPolicy.clearlyNonGameTitle("Example concert.mkv"))
        assertTrue(GameTorrentPolicy.clearlyNonGameTitle("Music FLAC discography"))
        assertFalse(GameTorrentPolicy.clearlyNonGameTitle("Mature Visual Novel Game"))
        assertFalse(GameTorrentPolicy.hasGamePayload(listOf(
            "Video compilation 2025.zip", "Movie 1080p.mkv")))
        assertTrue(GameTorrentPolicy.hasGamePayload(listOf(
            "Game Release.7z", "Game/Cutscene.mp4")))
    }
    @Test fun chineseGameIndexerSearchReturnsRealGameDetailsOnly(){
        val html="""<html><body>
          <a href="/view.so?id=12345" title="[RPG] Example Adventure Windows">Open</a>
          <a href="/view.so?id=12345" title="[RPG] Example Adventure Windows">Duplicate</a>
          <a href="/view.so?id=999" title="Movie video only">Movie</a>
          <a href="https://attacker.example/view.so?id=5" title="Example Adventure">Unsafe</a>
        </body></html>"""
        val results=MultiTorrentSearch.ggbasesListing(html,"Example Adventure")
        assertEquals(1,results.size)
        assertEquals("https://www.ggbases.com/view.so?id=12345",results.single().url)
        assertEquals(CatalogKind.TORRENT,results.single().kind)
    }
    @Test fun searchApisRejectInvalidHashes(){
        val h="a".repeat(40)
        val json="{\"results\":[{\"title\":\"Game\",\"infohash\":\""+h+"\",\"seeders\":5}]}"
        assertEquals(1,MultiTorrentSearch.bitsearch(json).size)
        assertTrue(MultiTorrentSearch.bitsearch(json)[0].url.startsWith("magnet:?"))
        val bay="[{\"name\":\"Game\",\"info_hash\":\""+h+"\",\"category\":\"401\"}]"
        assertEquals(1,MultiTorrentSearch.apibay(bay).size)
    }
}
