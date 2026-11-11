package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class ProviderRetirementTest {
    @Test fun removedProviderAndSubdomainAreNotActive(){
        assertTrue(ProviderRetirement.isRetired("https://www.pornhits.com/main/"))
        assertTrue(ProviderRetirement.isRetired("https://m.pornhits.com/video/example"))
        assertTrue(ProviderRetirement.isRetired("https://pornhits.com/video/example"))
    }
    @Test fun lookalikeAndUnrelatedHostsRemainIndependent(){
        assertFalse(ProviderRetirement.isRetired("https://pornhits.com.evil.example/"))
        assertFalse(ProviderRetirement.isRetired("https://mypornhits.com/"))
        assertFalse(ProviderRetirement.isRetired("https://www.omg.porn/"))
        assertFalse(ProviderRetirement.isRetired("https://hentaibros.net/"))
    }
}
