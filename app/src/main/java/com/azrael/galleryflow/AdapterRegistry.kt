package com.azrael.galleryflow
/** Original Kyora parsers first; generic HTML only as fallback. */
object AdapterRegistry {
    private val http = HttpClient()
    private val generic = GenericSiteAdapter(http)
    val adapters: List<SourceAdapter> = listOf(
        BuonDuaAdapter(http), KiutakuAdapter(http), FourKGirlAdapter(http),
        EveriaAdapter(http), FourKhdAdapter(http), CosplayTeleAdapter(http), generic
    )
    fun available(source: SourceId): Boolean = adapters.any { it.source == source }
    fun forUrl(url: String): SourceAdapter =
        adapters.firstOrNull { it.matches(url) }
            ?: throw AdapterException("Enter a supported HTTP or HTTPS gallery URL.")
    fun forSource(source: SourceId): SourceAdapter =
        adapters.firstOrNull { it.source == source }
            ?: throw AdapterException("No installed adapter for " + source.wireName)
    fun defaultEntities(): List<SourceEntity> =
        adapters.filter { it.enabled }.flatMap { it.defaultEntities() }
}
