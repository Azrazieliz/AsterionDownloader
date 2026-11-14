package com.azrael.galleryflow

import android.content.Context

/** Preserve the existing native Kyora parsers and generic fallback. Bind
 * a request-context HTTP client only when performing real network transfers,
 * so configured provider routes are actually honored for gallery images.
 */
object AdapterRegistry {
    private fun build(http:HttpClient):List<SourceAdapter> = listOf(
        BuonDuaAdapter(http), KiutakuAdapter(http), FourKGirlAdapter(http),
        EveriaAdapter(http), FourKhdAdapter(http), CosplayTeleAdapter(http),
        GenericSiteAdapter(http)
    )
    val adapters:List<SourceAdapter> = build(HttpClient())
    private fun candidates(context:Context?):List<SourceAdapter> =
        if(context==null)adapters else build(HttpClient(context.applicationContext))
    fun available(source:SourceId):Boolean = adapters.any{it.source==source}
    fun forUrl(url:String,context:Context?=null):SourceAdapter =
        candidates(context).firstOrNull{it.matches(url)}
            ?:throw AdapterException("Enter a supported HTTP or HTTPS gallery URL.")
    fun forSource(source:SourceId,context:Context?=null):SourceAdapter =
        candidates(context).firstOrNull{it.source==source}
            ?:throw AdapterException("No installed adapter for "+source.wireName)
    fun defaultEntities():List<SourceEntity> =
        adapters.filter{it.enabled}.flatMap{it.defaultEntities()}
}
