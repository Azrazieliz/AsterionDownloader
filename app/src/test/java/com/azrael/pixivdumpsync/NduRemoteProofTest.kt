package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class NduRemoteProofTest {
    private val path="/Nocturne/Books/chapter.epub"
    private val hash="a".repeat(32)

    @Test fun acceptsExactRemoteReceipt(){
        assertTrue(NduRemoteProof.matches(path,2048,hash,path,2048,128L,hash,true))
    }

    @Test fun rejectsMismatchedDestination(){
        assertFalse(NduRemoteProof.matches(path,2048,hash,
            "/Nocturne/Other/chapter.epub",2048,128L,hash))
    }

    @Test fun rejectsMismatchedSizeAndMissingFileId(){
        assertFalse(NduRemoteProof.matches(path,2048,hash,path,2047,128L,hash))
        assertFalse(NduRemoteProof.matches(path,2048,hash,path,2048,0L,hash))
    }

    @Test fun rejectsWrongHashAndMissingRequiredHash(){
        assertFalse(NduRemoteProof.matches(path,2048,hash,path,2048,128L,
            "b".repeat(32)))
        assertFalse(NduRemoteProof.matches(path,2048,hash,path,2048,128L,
            null,true))
    }

    @Test fun allowsProviderWithoutOptionalHashOnlyWithRemoteIdentity(){
        assertTrue(NduRemoteProof.matches(path,2048,hash,path,2048,128L,null))
    }
}
