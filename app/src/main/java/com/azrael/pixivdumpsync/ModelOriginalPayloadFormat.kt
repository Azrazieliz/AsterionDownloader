package com.azrael.pixivdumpsync

/** File signatures, not catalog assertions, select the saved model extension.
 * Validated for the existing federated download endpoint, which can legally
 * return a ZIP even when requested using format=glb.
 */
internal object ModelOriginalPayloadFormat {
    fun detect(first:ByteArray):String? {
        if(first.size>=4 && first[0]==0x67.toByte() &&
            first[1]==0x6c.toByte() && first[2]==0x54.toByte() &&
            first[3]==0x46.toByte())return "GLB"
        if(first.size>=4 && first[0]==0x50.toByte() &&
            first[1]==0x4b.toByte() &&
            (first[2]==0x03.toByte() || first[2]==0x05.toByte() ||
                first[2]==0x07.toByte()) &&
            (first[3]==0x04.toByte() || first[3]==0x06.toByte() ||
                first[3]==0x08.toByte()))return "ZIP"
        return null
    }
}
