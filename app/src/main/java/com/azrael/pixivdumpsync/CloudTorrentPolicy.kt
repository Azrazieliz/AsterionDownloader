package com.azrael.pixivdumpsync

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/**
 * Validates the selected BitTorrent v1 magnet before using TeraBox cloud_dl.
 * Magnet metadata is never interpreted as a local file path, HTTP URL, or
 * proof that a torrent has downloaded. No device-side torrent payload.
 */
internal object CloudTorrentPolicy {
    private val hex=Regex("^[0-9a-fA-F]{40}$")
    private val base32=Regex("^[A-Z2-7]{32}$")
    fun infoHash(magnet:String):String? {
        if(magnet.length !in 30..4096 || magnet.any{it.code<32})return null
        val uri=runCatching{URI(magnet)}.getOrNull()?:return null
        if(!uri.scheme.equals("magnet",ignoreCase=true) ||
            uri.rawFragment!=null || uri.rawAuthority!=null)return null
        val raw=uri.rawSchemeSpecificPart.orEmpty()
        if(!raw.startsWith("?"))return null
        val hashes=ArrayList<String>()
        for(parameter in raw.drop(1).split('&')){
            val pair=parameter.split('=',limit=2)
            if(!pair[0].equals("xt",ignoreCase=true))continue
            if(pair.size!=2)return null
            val exact=runCatching{
                URLDecoder.decode(pair[1],"UTF-8")
            }.getOrNull()?:return null
            if(!exact.startsWith("urn:btih:",ignoreCase=true))return null
            val value=exact.substringAfterLast(':')
            val normalized=when {
                hex.matches(value)->value.lowercase(Locale.ROOT)
                base32.matches(value.uppercase(Locale.ROOT))->
                    value.uppercase(Locale.ROOT)
                else->return null
            }
            hashes.add(normalized)
        }
        return hashes.singleOrNull()
    }
}
