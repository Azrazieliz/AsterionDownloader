package com.azrael.galleryflow

import org.junit.Assert.assertThrows
import org.junit.Test

class OriginalPayloadVerifierTest {
    @Test fun archiveMembersRequireBothOriginalSignatureAndDeclaredSize(){
        val png=byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)
        OriginalPayloadVerifier.verifyArchiveMember(MediaKind.IMAGE,4096,4096,png,png.size)
        assertThrows(AdapterException::class.java){
            OriginalPayloadVerifier.verifyArchiveMember(MediaKind.IMAGE,4095,4096,png,png.size)
        }
        val html="<html>not an image</html>".toByteArray()
        assertThrows(AdapterException::class.java){
            OriginalPayloadVerifier.verifyArchiveMember(
                MediaKind.IMAGE,html.size.toLong(),html.size.toLong(),html,html.size)
        }
    }

    @Test fun acceptsOriginalJpegPngAndWebp() {
        val jpeg=byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xe0.toByte(),1,2)
        OriginalPayloadVerifier.verify(MediaKind.IMAGE,4096,jpeg,jpeg.size)
        val png=byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)
        OriginalPayloadVerifier.verify(MediaKind.IMAGE,8192,png,png.size)
        val webp="RIFF0000WEBPVP8 ".toByteArray()
        OriginalPayloadVerifier.verify(MediaKind.IMAGE,4096,webp,webp.size)
    }
    @Test fun acceptsExtensionlessPublisherMp4AndZip(){
        val mp4="\u0000\u0000\u0000\u001cftypisom".toByteArray()
        OriginalPayloadVerifier.verify(MediaKind.VIDEO,123456,mp4,mp4.size)
        val zip=byteArrayOf(0x50,0x4b,0x03,0x04,1,2)
        OriginalPayloadVerifier.verify(MediaKind.ARCHIVE,512,zip,zip.size)
    }
    @Test fun rejectsMislabeledHtmlEvenWhenServerReturns200(){
        val html="<html><body>Verify your browser</body></html>".toByteArray()
        assertThrows(AdapterException::class.java) {
            OriginalPayloadVerifier.verify(MediaKind.IMAGE,html.size.toLong(),html,html.size)
        }
    }
    @Test fun rejectsImageThumbnailUrlThatReturnsJsonOrOtherMedia(){
        val payload="{\"error\":\"forbidden\"}".toByteArray()
        assertThrows(AdapterException::class.java) {
            OriginalPayloadVerifier.verify(MediaKind.IMAGE,payload.size.toLong(),payload,payload.size)
        }
        val video="\u0000\u0000\u0000\u001cftypisom".toByteArray()
        assertThrows(AdapterException::class.java){
            OriginalPayloadVerifier.verify(MediaKind.IMAGE,5000,video,video.size)
        }
    }
    @Test fun rejectsEmptyAndUnknownPayloads(){
        val bad=byteArrayOf(1,2,3,4)
        assertThrows(AdapterException::class.java){
            OriginalPayloadVerifier.verify(MediaKind.ARCHIVE,100,bad,bad.size)
        }
        assertThrows(AdapterException::class.java){
            OriginalPayloadVerifier.verify(MediaKind.IMAGE,0,bad,bad.size)
        }
    }
}
