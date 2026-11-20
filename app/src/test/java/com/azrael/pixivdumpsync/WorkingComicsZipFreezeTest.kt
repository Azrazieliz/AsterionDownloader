package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Owner-reported working Toonily/MangaRead chapter ZIP implementation.
 * This baseline must not silently change while repairing unrelated
 * providers, model search, or video parallelism.
 */
class WorkingComicsZipFreezeTest {
    private val frozen=linkedMapOf(
        "ChapterArchives.kt" to "3f6e630eed5e75cd6d1f0f57f2133815ef11547b",
        "ChapterArchiveJobService.kt" to "ba5539405d26b4e5052989e6bf32fb7573d355b5"
    )
    @Test fun ownerApprovedComicsZipTransferStaysFrozen(){
        assertEquals(2,frozen.size)
        val cwd=File(System.getProperty("user.dir") ?: ".")
        val module=if(File(cwd,"src/main/java").isDirectory)cwd
            else File(cwd,"app")
        frozen.forEach{(name,sha)->
            val target=File(module,
                "src/main/java/com/azrael/pixivdumpsync/"+name)
            assertTrue("Frozen ZIP implementation missing: "+name,
                target.isFile)
            val bytes=target.readText(Charsets.UTF_8)
                .replace("\r\n","\n").toByteArray(Charsets.UTF_8)
            val digest=MessageDigest.getInstance("SHA-1")
            digest.update("blob ${bytes.size}\u0000"
                .toByteArray(StandardCharsets.US_ASCII))
            digest.update(bytes)
            assertEquals("Working image ZIP flow changed: "+name,sha,
                digest.digest().joinToString(""){
                    "%02x".format(it.toInt() and 255)
                })
        }
    }
}
