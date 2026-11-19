package com.azrael.pixivdumpsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BencodedTorrentManifestTest {
    private fun encode(value:Any):String=when(value){
        is String -> value.toByteArray(Charsets.UTF_8).size.toString()+":"+value
        is Number -> "i"+value+"e"
        is List<*> -> "l"+value.joinToString(""){encode(requireNotNull(it))}+"e"
        is Map<*,*> -> "d"+value.keys.map{it as String}.sorted().joinToString(""){key->
            encode(key)+encode(requireNotNull(value[key]))
        }+"e"
        else->error("Unsupported fixture")
    }
    private fun descriptor(info:Map<String,Any>):ByteArray =
        encode(mapOf("announce" to "https://example.org/announce","info" to info))
            .toByteArray(Charsets.UTF_8)

    @Test fun singleFileIsInspectableWithoutNativeEngine(){
        val info=mapOf<String,Any>("name" to "demo.zip","length" to 4096L,
            "piece length" to 16384L,"pieces" to "12345678901234567890")
        val manifest=BencodedTorrentManifest.parse(descriptor(info))
        assertEquals("demo.zip",manifest.name)
        assertEquals(listOf("demo.zip"),manifest.files.map{it.path})
        assertEquals(4096L,manifest.files.first().size)
    }
    @Test fun nestedMultiFilePathsAndSizesArePreserved(){
        val info=mapOf<String,Any>("name" to "Example Game","piece length" to 16384,
            "pieces" to "12345678901234567890",
            "files" to listOf(
                mapOf("length" to 2048,"path" to listOf("data","sprites.zip")),
                mapOf("length" to 12,"path" to listOf("run.exe"))
            ))
        val parsed=BencodedTorrentManifest.parse(descriptor(info))
        assertEquals(listOf("Example Game/data/sprites.zip","Example Game/run.exe"),
            parsed.files.map{it.path})
        assertEquals(listOf(2048L,12L),parsed.files.map{it.size})
    }
    @Test fun parentDirectoryTraversalIsRejected(){
        val info=mapOf<String,Any>("name" to "Demo","files" to listOf(
            mapOf("length" to 8,"path" to listOf("..","escape.exe"))
        ))
        val attempt=runCatching{BencodedTorrentManifest.parse(descriptor(info))}
        assertTrue(attempt.isFailure)
    }
    @Test fun duplicatePathsAreRejected(){
        val info=mapOf<String,Any>("name" to "Demo","files" to listOf(
            mapOf("length" to 8,"path" to listOf("same.zip")),
            mapOf("length" to 9,"path" to listOf("same.zip"))
        ))
        assertTrue(runCatching{
            BencodedTorrentManifest.parse(descriptor(info))
        }.isFailure)
    }
    @Test fun v2OnlyWithoutV1FileTreeIsNotPretendedToWork(){
        val info=mapOf<String,Any>("name" to "Demo","meta version" to 2,
            "file tree" to mapOf("item.bin" to mapOf("" to mapOf("length" to 8))))
        assertTrue(runCatching{
            BencodedTorrentManifest.parse(descriptor(info))
        }.isFailure)
    }
    @Test fun truncatedDescriptorFailsSafely(){
        val info=mapOf<String,Any>("name" to "demo.zip","length" to 4L)
        val bytes=descriptor(info)
        assertTrue(runCatching{
            BencodedTorrentManifest.parse(bytes.copyOf(bytes.size-3))
        }.isFailure)
    }
}
