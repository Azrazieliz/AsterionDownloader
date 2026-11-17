package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class FileSaveLockPolicyTest {
    @Test fun independentPixivFilesAreNotSerializedGlobally() {
        val firstName="123456_p0.jpg"
        val firstHash=(firstName.hashCode() and Int.MAX_VALUE) % 64
        val secondName=(1..200).map{it.toString()+"_p1.png"}
            .first { (it.hashCode() and Int.MAX_VALUE) % 64 != firstHash }
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        val independent=CountDownLatch(1)
        val first=thread(name="pixiv-original-one") {
            FileSaveLockPolicy.forFilename(firstName) {
                entered.countDown()
                release.await(3,TimeUnit.SECONDS)
            }
        }
        try {
            assertTrue("First original did not start",entered.await(2,TimeUnit.SECONDS))
            val second=thread(name="pixiv-original-two") {
                FileSaveLockPolicy.forFilename(secondName) {
                    independent.countDown()
                }
            }
            assertTrue("Independent originals must not share global download lock",
                independent.await(2,TimeUnit.SECONDS))
            second.join(2000)
            assertFalse(second.isAlive)
        } finally {
            release.countDown()
            first.join(3000)
        }
    }

    @Test fun sameFilenameIsStillSerialized() {
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        val secondStarted=CountDownLatch(1)
        val secondFinished=CountDownLatch(1)
        val first=thread {
            FileSaveLockPolicy.forFilename("123_p0.jpg") {
                entered.countDown()
                release.await(3,TimeUnit.SECONDS)
            }
        }
        assertTrue(entered.await(2,TimeUnit.SECONDS))
        val second=thread {
            secondStarted.countDown()
            FileSaveLockPolicy.forFilename("123_p0.jpg"){secondFinished.countDown()}
        }
        try {
            assertTrue(secondStarted.await(2,TimeUnit.SECONDS))
            assertFalse("Duplicate filename raced with active writer",
                secondFinished.await(100,TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            first.join(3000)
            second.join(3000)
        }
        assertEquals(0L,secondFinished.count)
    }
}
