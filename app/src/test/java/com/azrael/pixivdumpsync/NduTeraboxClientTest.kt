package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class NduTeraboxClientTest {
    @Test fun chunkSizeScalesAtMultiGigabyteBoundaries(){
        val gib=1024L*1024*1024
        assertEquals(4*1024*1024,NduTeraboxClient.chunkSizeFor(1))
        assertEquals(4*1024*1024,NduTeraboxClient.chunkSizeFor(4*gib))
        assertEquals(8*1024*1024,NduTeraboxClient.chunkSizeFor(4*gib+1))
        assertEquals(16*1024*1024,NduTeraboxClient.chunkSizeFor(8*gib+1))
        assertEquals(128*1024*1024,NduTeraboxClient.chunkSizeFor(128*gib))
    }

    @Test fun chunkUploadHostsMustBeInTrustedDomain(){
        assertEquals("jp-data.terabox.com",
            NduTeraboxClient.checkedUploadHost("jp-data.terabox.com"))
        for(host in listOf("terabox.com.evil.example","http://terabox.com",
                         "evil.example","127.0.0.1")){
            try{
                NduTeraboxClient.checkedUploadHost(host)
                fail("Accepted untrusted upload destination: "+host)
            }catch(expected:IllegalArgumentException){ }
        }
    }
}
