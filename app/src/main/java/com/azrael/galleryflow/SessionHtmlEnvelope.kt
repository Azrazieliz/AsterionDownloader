package com.azrael.galleryflow

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Bounded, same-UID cross-process transport for large authenticated DOM snapshots.
 * Android Binder can reject a raw HTML page near 1 MiB even though the page
 * renders correctly in the owner's main-process WebView.
 */
internal object SessionHtmlEnvelope {
    const val RAW_LIMIT=300_000
    const val ENCODED_LIMIT=580_000
    const val DECODED_LIMIT=2_500_000

    fun compress(html:String):String?=runCatching {
        val bytes=html.toByteArray(Charsets.UTF_8)
        if(bytes.size>DECODED_LIMIT)return@runCatching null
        val buffer=ByteArrayOutputStream()
        GZIPOutputStream(buffer).use{it.write(bytes)}
        Base64.getEncoder().encodeToString(buffer.toByteArray())
            .takeIf{it.length<=ENCODED_LIMIT}
    }.getOrNull()

    fun expand(encoded:String):String?=runCatching {
        if(encoded.length !in 1..ENCODED_LIMIT)return@runCatching null
        val source=Base64.getDecoder().decode(encoded)
        val output=ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(source)).use{input->
            val data=ByteArray(16_384)
            while(true){
                val n=input.read(data)
                if(n<0)break
                if(output.size()+n>DECODED_LIMIT)return@runCatching null
                output.write(data,0,n)
            }
        }
        output.toString(Charsets.UTF_8.name())
    }.getOrNull()
}
