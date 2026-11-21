package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class ModelOriginalPayloadFormatTest {
    @Test fun glbMagicRemainsNativeGlb(){
        assertEquals("GLB",ModelOriginalPayloadFormat.detect(
            byteArrayOf(0x67,0x6c,0x54,0x46,0x02,0x00,0x00,0x00)))
    }
    @Test fun verifiedFederatedBlenderKitZipIsNotMislabeledGlb(){
        assertEquals("ZIP",ModelOriginalPayloadFormat.detect(
            byteArrayOf(0x50,0x4b,0x03,0x04,0x14,0x00,0x08,0x00)))
    }
    @Test fun loginHtmlAndTruncatedDataCannotQueueFakeOriginal(){
        assertNull(ModelOriginalPayloadFormat.detect(
            "<!doctype html".toByteArray()))
        assertNull(ModelOriginalPayloadFormat.detect(byteArrayOf(0x50,0x4b)))
        assertNull(ModelOriginalPayloadFormat.detect(byteArrayOf()))
    }
}
