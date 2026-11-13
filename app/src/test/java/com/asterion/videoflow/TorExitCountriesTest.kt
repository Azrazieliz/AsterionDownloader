package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class TorExitCountriesTest {
    @Test fun selectedCountryUsesActualTorExitNodes(){
        assertEquals(
            "ClientOnly 1\nSafeSocks 1\nExitNodes {us}\nStrictNodes 1\n",
            TorExitCountries.torrc("us"))
        assertEquals("ClientOnly 1\nSafeSocks 1\n",
            TorExitCountries.torrc(""))
    }
    @Test fun arbitraryCountryCodeIsNotSilentlyAdmitted(){
        assertThrows(IllegalArgumentException::class.java){
            TorExitCountries.torrc("zz")
        }
    }
}
