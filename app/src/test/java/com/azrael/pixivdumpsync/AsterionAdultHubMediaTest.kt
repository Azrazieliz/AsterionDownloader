package com.azrael.pixivdumpsync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsterionAdultHubMediaTest {
    private fun entry(url:String,area:String="HENTAI")=IndexedItem(
        url,"Actual Episode", "https://hentaibros.net/img/post.webp",
        "https://hentaibros.net/",area,"video",1L,false)

    @Test fun firstPartySingleSlugHentaiIsNotDiscardedFromMediaLibrary(){
        assertTrue(AsterionAdultHub.realMedia(entry(
            "https://hentaibros.net/muramata-san-no-aijou-episode-2/")))
    }
    @Test fun navigationAndForeignHostDoNotBecomeFirstPartyMedia(){
        assertFalse(AsterionAdultHub.realMedia(entry(
            "https://hentaibros.net/latest-videos/")))
        assertFalse(AsterionAdultHub.realMedia(entry(
            "https://foreign.example/video/actual-episode/")))
    }
    @Test fun pimpbunnyCreatorPortraitIsNotIndexedAsVideo(){
        val source="https://pimpbunny.com/"
        val cover="https://pimpbunny.com/thumbs/model.jpg"
        val model="https://pimpbunny.com/models/lolabunny-yu/"
        val video="https://pimpbunny.com/videos/actual-scene-123/"
        assertFalse(KyoraCreatorPreviews.acceptedVideoCard(source,model))
        assertTrue(KyoraCreatorPreviews.acceptedVideoCard(source,video))
        assertFalse(AsterionAdultHub.publicMedia(model,"Lolabunny",cover))
        assertTrue(AsterionAdultHub.publicMedia(video,"Actual scene",cover))
        assertFalse(AsterionAdultHub.realMedia(IndexedItem(
            model,"Lolabunny",cover,source,"ADULT","video",1L,false)))
        assertTrue(AsterionAdultHub.realMedia(IndexedItem(
            video,"Actual scene",cover,source,"ADULT","video",1L,false)))
    }

}
