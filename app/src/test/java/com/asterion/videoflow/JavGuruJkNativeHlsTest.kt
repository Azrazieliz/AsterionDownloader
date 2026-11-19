package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class JavGuruJkNativeHlsTest {
    private val article="https://jav.guru/1234567/owner-selected-original/"
    private val embed="https://maxstream.org/e/OwnerSelected1234"
    private val master="https://s4.maxstream.org/selected/master.m3u8?sig=firstParty"

    @Test fun onlyPublisherSelectedJkCdnsUseThisLane(){
        assertTrue(JavGuruJkNativeHls.eligible(article,master,embed))
        for(url in listOf(
            "http://s4.maxstream.org/selected/master.m3u8?sig=x",
            "https://s4.maxstream.org.evil.example/selected/master.m3u8?sig=x",
            "https://ads.example/selected/master.m3u8?sig=x",
            "https://s4.maxstream.org/selected/master.m3u8",
            "https://s4.maxstream.org/selected/preview.mp4?sig=x"
        )) assertFalse(url,JavGuruJkNativeHls.eligible(article,url,embed))
        assertFalse(JavGuruJkNativeHls.eligible(
            "https://ads.example/1234567/original/",master,embed))
        assertFalse(JavGuruJkNativeHls.eligible(
            article,master,"https://maxstream.org.evil.example/e/OwnerSelected1234"))
    }

    @Test fun onlyOneFirstParty720pOriginalVariantIsAccepted(){
        val fixture="""
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            360p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1544578,RESOLUTION=1280x720
            720p.m3u8
        """.trimIndent()
        assertEquals("https://s4.maxstream.org/selected/720p.m3u8",
            JavGuruJkNativeHls.selectedVariant(master,fixture))
        assertThrows(java.io.IOException::class.java) {
            JavGuruJkNativeHls.selectedVariant(master,fixture.replace(
                "720p.m3u8","https://ads.example/720p.m3u8"))
        }
        assertThrows(java.io.IOException::class.java) {
            JavGuruJkNativeHls.selectedVariant(master,fixture.replace(
                "RESOLUTION=1280x720","RESOLUTION=1920x1080"))
        }
    }

    private fun vod(final:Boolean=true,publicHost:String=""):String=buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
        appendLine("#EXT-X-MEDIA-SEQUENCE:1")
        appendLine("#EXT-X-KEY:METHOD=AES-128,URI=\"key.key\"")
        repeat(105){ i ->
            appendLine("#EXTINF:10.0,")
            appendLine(if(publicHost.isBlank())"segment-"+i+".ts" else
                "https://"+publicHost+"/segment-"+i+".ts")
        }
        if(final)appendLine("#EXT-X-ENDLIST")
    }

    @Test fun exactFinishedSourceRequiresCompleteSegmentsAndKey(){
        val media="https://s4.maxstream.org/selected/720p.m3u8"
        val plan=JavGuruJkNativeHls.selectedPlan(media,vod())
        assertEquals(105,plan.segments.size)
        assertEquals(1050.0,plan.durationSeconds,0.01)
        assertEquals(1L,plan.mediaSequence)
        assertEquals("https://s4.maxstream.org/selected/key.key",plan.keyUrl)
        assertThrows(java.io.IOException::class.java) {
            JavGuruJkNativeHls.selectedPlan(media,vod(final=false))
        }
        assertThrows(java.io.IOException::class.java) {
            JavGuruJkNativeHls.selectedPlan(media,vod(publicHost="ads.example"))
        }
        assertThrows(java.io.IOException::class.java) {
            JavGuruJkNativeHls.selectedPlan(media,vod().replace(
                "METHOD=AES-128","METHOD=SAMPLE-AES"))
        }
        assertThrows(java.io.IOException::class.java) {
            JavGuruJkNativeHls.selectedPlan(media,vod().replace(
                "segment-1.ts","segment-0.ts"))
        }
    }

    @Test fun publisherDeclaredAes128IsDecodedWithoutGuessingKeys(){
        val key=ByteArray(16){(it+3).toByte()}
        val iv=ByteArray(16){(it+11).toByte()}
        val clear=ByteArray(188*8){i->if(i%188==0)0x47.toByte() else 0x10.toByte()}
        val pad=ByteArray(16){16}
        val cipher=Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),IvParameterSpec(iv))
        val encrypted=cipher.doFinal(clear+pad)
        assertArrayEquals(clear,JavGuruJkNativeHls.decodeSegment(encrypted,key,iv))
        assertThrows(java.io.IOException::class.java) {
            JavGuruJkNativeHls.decodeSegment(encrypted,ByteArray(15),iv)
        }
        assertThrows(java.io.IOException::class.java) {
            JavGuruJkNativeHls.decodeSegment(
                encrypted.copyOf(encrypted.size-1),key,iv)
        }
    }

    @Test fun signedQueryRotationCannotMixDifferentSelectedOriginals() {
        val variant="https://s4.maxstream.org/selected/720p.m3u8?sig=firstParty"
        val body=vod()
        val plan=JavGuruJkNativeHls.selectedPlan(variant,body)
        val durations=body.lines().map{it.trim()}.filter{it.startsWith("#EXTINF:")}
        val key=ByteArray(16){(it+1).toByte()}
        fun fingerprint(
            selected:String=article,
            playlist:String=master,
            mediaPlan:JavGuruJkNativeHls.Plan=plan,
            mediaKey:ByteArray=key
        )=JavGuruJkNativeHls.stageFingerprint(
            selected,playlist,embed,variant,mediaPlan,mediaKey,durations)
        val original=fingerprint()
        assertEquals(original,fingerprint(
            playlist=master.replace("firstParty","renewedSignature")))
        assertNotEquals(original,fingerprint(
            selected="https://jav.guru/1234568/other-selected-original/"))
        assertNotEquals(original,fingerprint(
            mediaPlan=plan.copy(mediaSequence=plan.mediaSequence+1)))
        assertNotEquals(original,fingerprint(
            mediaKey=ByteArray(16){(it+2).toByte()}))
        assertNotEquals(original,fingerprint(
            mediaPlan=plan.copy(segments=plan.segments.mapIndexed{index,uri->
                if(index==2)uri.replace("segment-2.ts","new-segment-2.ts") else uri
            })))
    }

    @Test fun interruptedSelectedHlsFragmentsRequireAnExactSavedChecksum(){
        val dir=java.nio.file.Files.createTempDirectory("jk-source-fixture").toFile()
        try {
            val file=java.io.File(dir,"fragment-0000.ts")
            val bytes=ByteArray(188*8){index->
                if(index%188==0)0x47.toByte() else 0x10.toByte()
            }
            file.writeBytes(bytes)
            assertFalse(JavGuruJkNativeHls.reusableFragment(file))
            val digest=java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes).joinToString(""){"%02x".format(it.toInt() and 0xff)}
            val receipt=java.io.File(dir,file.name+".sha256")
            receipt.writeText(digest)
            assertTrue(JavGuruJkNativeHls.reusableFragment(file))
            file.writeBytes(bytes.copyOf().apply{this[17]=0x55})
            assertFalse(JavGuruJkNativeHls.reusableFragment(file))
            file.writeBytes(bytes)
            assertTrue(JavGuruJkNativeHls.reusableFragment(file))
            receipt.writeText("bad-receipt")
            assertFalse(JavGuruJkNativeHls.reusableFragment(file))
        } finally {
            dir.listFiles()?.forEach{it.delete()}
            dir.delete()
        }
    }

    @Test fun nativeSelectedHlsPercentageMeasuresCompletedSegmentsOnly(){
        assertEquals(0,JavGuruJkNativeHls.fragmentPercent(0,751))
        assertEquals(44,JavGuruJkNativeHls.fragmentPercent(375,751))
        assertEquals(90,JavGuruJkNativeHls.fragmentPercent(751,751))
        assertThrows(IllegalArgumentException::class.java) {
            JavGuruJkNativeHls.fragmentPercent(752,751)
        }
    }

}
