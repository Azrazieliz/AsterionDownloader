package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NativeMediaReceiptTest {
    @Test fun copiedMediaStoreBytesMustMatchPrivateSelectedOriginalHash(){
        val original=ByteArray(1_000_131){i->(i%251).toByte()}
        val output=java.io.ByteArrayOutputStream()
        val digest=NativeMediaReceipt.copyAndDigest(
            java.io.ByteArrayInputStream(original),output)
        assertEquals(32,digest.size)
        assertTrue(NativeMediaReceipt.matchesSavedCopy(
            java.io.ByteArrayInputStream(output.toByteArray()),digest))
        val wrong=output.toByteArray()
        wrong[512]=(wrong[512].toInt() xor 0x01).toByte()
        assertFalse(NativeMediaReceipt.matchesSavedCopy(
            java.io.ByteArrayInputStream(wrong),digest))
        assertFalse(NativeMediaReceipt.matchesSavedCopy(
            java.io.ByteArrayInputStream(original),ByteArray(31)))
    }

    @Test fun completedFileRequiresPersistedExactLengthAndHash(){
        val dir=Files.createTempDirectory("asterion-native-receipt").toFile()
        try{
            val file=File(dir,"source.mp4")
            val receipt=File(dir,"source.mp4.receipt")
            file.writeBytes(ByteArray(1024){(it%251).toByte()})
            assertFalse(NativeMediaReceipt.matches(file,receipt))
            NativeMediaReceipt.write(file,receipt)
            assertTrue(NativeMediaReceipt.matches(file,receipt))
            file.appendBytes(byteArrayOf(11,22))
            assertFalse(NativeMediaReceipt.matches(file,receipt))
            file.writeBytes(ByteArray(1024){(it%17).toByte()})
            assertFalse(NativeMediaReceipt.matches(file,receipt))
        }finally{dir.deleteRecursively()}
    }

    @Test fun oldNonemptyDestinationWithoutReceiptIsNotAccepted(){
        val dir=Files.createTempDirectory("asterion-old-partial").toFile()
        try{
            val media=File(dir,"native-original.mp4")
            media.writeBytes(byteArrayOf(1,2,3,4))
            assertFalse(NativeMediaReceipt.matches(
                media,File(dir,"native-original.mp4.receipt")))
        }finally{dir.deleteRecursively()}
    }

    @Test fun truncatedOrForgedReceiptCannotAuthorizeMedia(){
        val dir=Files.createTempDirectory("asterion-forged-receipt").toFile()
        try{
            val media=File(dir,"scene.mkv")
            val receipt=File(dir,"scene.mkv.receipt")
            media.writeBytes(byteArrayOf(0x1a,0x45,0x22))
            receipt.writeText("3|"+("0".repeat(64))+"\n")
            assertFalse(NativeMediaReceipt.matches(media,receipt))
        }finally{dir.deleteRecursively()}
    }
}
