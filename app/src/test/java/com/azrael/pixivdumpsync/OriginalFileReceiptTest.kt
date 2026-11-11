package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

/** DownloadManager transport success is not proof of an original file. */
class OriginalFileReceiptTest {
    @Test fun rejectHtmlSavedAsZipOrImage(){
        val html="<html><title>Access denied</title></html>"
            .toByteArray(Charsets.UTF_8)
        val zip=OriginalFileReceipt.inspect("book.epub",html,html,5000)
        assertFalse(zip.valid)
        assertTrue(zip.reason.contains("HTML"))
        assertFalse(OriginalFileReceipt.inspect("artwork.png",html,byteArrayOf(),5000).valid)
        assertFalse(OriginalFileReceipt.inspect("video.mp4",html,byteArrayOf(),5000).valid)
    }
    @Test fun recognizeVerifiedImageAndVideoHeaders(){
        val png=byteArrayOf(-119,80,78,71,13,10,26,10)+ByteArray(32)
        assertTrue(OriginalFileReceipt.inspect("original.png",png,byteArrayOf(),10000).valid)
        val mp4=byteArrayOf(0,0,0,24)+
            "ftypisom0000".toByteArray(Charsets.US_ASCII)
        assertTrue(OriginalFileReceipt.inspect("video.mp4",mp4,byteArrayOf(),10000).valid)
        assertFalse(OriginalFileReceipt.inspect("video.mkv",mp4,byteArrayOf(),10000).valid)
    }
    @Test fun requireZipCentralDirectoryEndNotJustHeader(){
        val head=byteArrayOf(0x50,0x4b,0x03,0x04)+ByteArray(64)
        val missingEnd=OriginalFileReceipt.inspect("asset.zip",head,ByteArray(64),1024)
        assertFalse(missingEnd.valid)
        val end=byteArrayOf(0x50,0x4b,0x05,0x06)+ByteArray(18)
        assertTrue(OriginalFileReceipt.inspect("asset.zip",head,end,1024).valid)
        assertTrue(OriginalFileReceipt.inspect("book.epub",head,end,1024).valid)
    }
    @Test fun rejectShortAndMisidentifiedFiles(){
        assertFalse(OriginalFileReceipt.inspect("asset.zip",ByteArray(10),ByteArray(22),10).valid)
        val gif="GIF89a".toByteArray()+ByteArray(24)
        assertTrue(OriginalFileReceipt.inspect("anim.gif",gif,ByteArray(0),2048).valid)
        assertFalse(OriginalFileReceipt.inspect("anim.webp",gif,ByteArray(0),2048).valid)
    }
    @Test fun realArchivePassesAndTruncationFails(){
        val output=java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use{zip->
            zip.putNextEntry(java.util.zip.ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("OEBPS/chapter.xhtml"))
            zip.write("<html>Chapter one</html>".toByteArray())
            zip.closeEntry()
        }
        val bytes=output.toByteArray()
        fun inspect(data:ByteArray)=OriginalFileReceipt.inspect("real.epub",
            data.copyOfRange(0,minOf(512,data.size)),
            data.copyOfRange(maxOf(0,data.size-65557),data.size),data.size.toLong())
        assertTrue(inspect(bytes).valid)
        assertFalse(inspect(bytes.copyOf(bytes.size-15)).valid)
    }
    @Test fun errorJsonIsNotAnAssetOriginal(){
        val body="""{"error":"authentication required"}"""
            .padEnd(4096,' ').toByteArray()
        assertFalse(OriginalFileReceipt.inspect("source.bin",
            body.copyOf(512),ByteArray(0),body.size.toLong()).valid)
    }
}
