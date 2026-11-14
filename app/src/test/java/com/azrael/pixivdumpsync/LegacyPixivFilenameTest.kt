package com.azrael.pixivdumpsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyPixivFilenameTest {
    @Test fun KurohaFilesRecoverExactArtworkAndPageIds(){
        assertEquals(LegacyPixivFilename.Page("12345678",0),
            LegacyPixivFilename.parse("12345678_p0.jpg"))
        assertEquals(LegacyPixivFilename.Page("12345678",12),
            LegacyPixivFilename.parse("12345678_p12.PNG"))
        assertEquals(LegacyPixivFilename.Page("123456789",1),
            LegacyPixivFilename.parse("123456789_p1.webp"))
        assertEquals(LegacyPixivFilename.Page("123456789",9999),
            LegacyPixivFilename.parse("123456789_p9999.avif"))
    }
    @Test fun RejectsThumbnailsAndAmbiguousOrMaliciousPaths(){
        for(name in listOf("preview_12345678_p0.jpg","12345678_p0_thumb.jpg",
            "12345678.jpg","12345678_p0.mp4","12345678_p10000.png",
            "folder/12345678_p0.jpg","../12345678_p0.jpg",
            "x12345678_p0.jpg","12345678_p-1.png","000_p1.png")){
            assertNull(name,LegacyPixivFilename.parse(name))
        }
    }
}
