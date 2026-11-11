package com.azrael.galleryflow

import org.junit.Assert.*
import org.junit.Test

/** The working Kyora six-adapter registry is the canonical production contract. */
class CoreOnlyRegistryTest {
    @Test fun canonicalKyoraAdaptersAndGenericFallbackShip() {
        assertEquals(setOf(
            SourceId.BUONDUA, SourceId.KIUTAKU, SourceId.FOUR_K_GIRL,
            SourceId.EVERIA, SourceId.FOUR_K_HD, SourceId.COSPLAYTELE,
            SourceId.GENERIC
        ), AdapterRegistry.adapters.map { it.source }.toSet())
        assertEquals(7, AdapterRegistry.adapters.size)
    }

    @Test fun realProvidersOverrideGenericButUnknownSitesRemainAddable() {
        assertEquals(SourceId.BUONDUA,
            AdapterRegistry.forUrl("https://buondua.net/albums/example").source)
        assertEquals(SourceId.FOUR_K_HD,
            AdapterRegistry.forUrl("https://www.4khd.com/content/test.html").source)
        assertEquals(SourceId.COSPLAYTELE,
            AdapterRegistry.forUrl("https://cosplaytele.com/gallery").source)
        assertEquals(SourceId.KIUTAKU,
            AdapterRegistry.forUrl("https://kiutaku.com/tag/123").source)
        assertEquals(SourceId.GENERIC,
            AdapterRegistry.forUrl("https://example.com/creator/gallery").source)
    }

    @Test fun historicRetiredBooruIdsRemainReadableWithoutEnablingThem() {
        assertEquals(SourceId.RULE34, SourceId.fromWire("rule34"))
        assertFalse(AdapterRegistry.available(SourceId.RULE34))
        assertFalse(AdapterRegistry.available(SourceId.DANBOORU))
        assertTrue(AdapterRegistry.available(SourceId.FOUR_K_HD))
        assertTrue(AdapterRegistry.available(SourceId.EVERIA))
        try {
            AdapterRegistry.forSource(SourceId.E621)
            fail("Unexpected retired booru adapter")
        } catch (expected: AdapterException) {
            assertTrue(expected.message.orEmpty().contains("No installed adapter"))
        }
    }
}
