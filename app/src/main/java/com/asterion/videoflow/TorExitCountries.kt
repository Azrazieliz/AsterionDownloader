package com.asterion.videoflow

/** Country selection affects Tor's actual exit relay; it never spoofs
 * HTTP headers or falsely asserts a different location.
 */
internal object TorExitCountries {
    val choices=listOf(
        "Any available country" to "",
        "United States" to "us",
        "Germany" to "de",
        "Netherlands" to "nl",
        "Switzerland" to "ch",
        "Canada" to "ca",
        "United Kingdom" to "gb",
        "Sweden" to "se",
        "Japan" to "jp"
    )
    fun torrc(country:String):String{
        require(country=="" || choices.any{it.second==country}){
            "Unsupported exit-country selection"
        }
        // With a selected country, never quietly fall back to another exit.
        val location=if(country.isBlank())""
            else "ExitNodes {$country}\nStrictNodes 1\n"
        return "ClientOnly 1\nSafeSocks 1\n"+location
    }
}
