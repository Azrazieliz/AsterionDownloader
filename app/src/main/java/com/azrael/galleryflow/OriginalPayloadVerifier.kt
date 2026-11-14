package com.azrael.galleryflow

/**
 * The publisher may return an HTTP 200 for a login/verification page or a
 * failed CDN proxy. Never count that response as a saved original image,
 * video or archive merely because the URL has a file suffix or the server
 * advertises a content type.
 *
 * This is a lightweight transport signature gate, not a complete decoder.
 * The stored byte count and downstream reader remain separately checked.
 */
internal object OriginalPayloadVerifier {
    private fun byte(data:ByteArray,i:Int):Int = data[i].toInt() and 0xff
    private fun ascii(data:ByteArray,offset:Int,length:Int):String =
        String(data,offset,length,Charsets.US_ASCII)
    private fun starts(data:ByteArray,n:Int,vararg expected:Int):Boolean =
        n>=expected.size && expected.indices.all{byte(data,it)==expected[it]}

    private fun signature(data:ByteArray,n:Int):String? {
        if(starts(data,n,0xff,0xd8,0xff))return "jpeg"
        if(starts(data,n,0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a))return "png"
        if(n>=6 && ascii(data,0,6) in setOf("GIF87a","GIF89a"))return "gif"
        if(n>=12 && ascii(data,0,4)=="RIFF" &&
            ascii(data,8,4)=="WEBP")return "webp"
        if(n>=12 && ascii(data,4,4)=="ftyp"){
            val brands=ascii(data,8,minOf(n-8,24))
            if(listOf("avif","avis").any{brands.contains(it)})return "avif"
            return "mp4"
        }
        if(starts(data,n,0x1a,0x45,0xdf,0xa3))return "webm"
        if(n>=4 && ascii(data,0,3)=="FLV")return "flv"
        if(starts(data,n,0x50,0x4b,0x03,0x04) ||
            starts(data,n,0x50,0x4b,0x05,0x06))return "zip"
        if(starts(data,n,0x52,0x61,0x72,0x21,0x1a,0x07))return "rar"
        if(starts(data,n,0x37,0x7a,0xbc,0xaf,0x27,0x1c))return "7z"
        return null
    }

    internal fun verify(kind:MediaKind,bytes:Long,first:ByteArray,firstSize:Int){
        if(bytes<=0 || firstSize<=0)throw AdapterException("Empty original response")
        val n=firstSize.coerceIn(0,first.size)
        // Always reject HTML regardless of a forged image/video MIME header.
        val prefix=ascii(first,0,minOf(n,32)).trimStart()
            .lowercase(java.util.Locale.ROOT)
        if(prefix.startsWith("<!doctype") || prefix.startsWith("<html") ||
            prefix.startsWith("<?xml") || prefix.startsWith("{\"error\"") ||
            prefix.startsWith("{\"message\""))
            throw AdapterException("Original URL returned a webpage or error payload")
        val detected=signature(first,n)
        val allowed=when(kind){
            MediaKind.IMAGE->setOf("jpeg","png","gif","webp","avif")
            MediaKind.VIDEO->setOf("mp4","webm","flv")
            MediaKind.ARCHIVE->setOf("zip","rar","7z")
        }
        if(detected !in allowed)
            throw AdapterException("Original payload signature is not valid "+
                kind.name.lowercase(java.util.Locale.ROOT)+" data")
    }
}
