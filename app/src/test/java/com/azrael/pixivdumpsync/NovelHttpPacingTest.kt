package com.azrael.pixivdumpsync

import org.junit.Assert.assertEquals
import org.junit.Test

class NovelHttpPacingTest {
    @Test fun freeWebNovelReaderIsPacedAcrossAllWorkers(){
        assertEquals(2_500L,NovelHttpPacing.minimumGapMillis("freewebnovel.com"))
        assertEquals(2_500L,NovelHttpPacing.minimumGapMillis("www.freewebnovel.com"))
        assertEquals(1_750L,NovelHttpPacing.minimumGapMillis("novelbuddy.me"))
        assertEquals(1_750L,NovelHttpPacing.minimumGapMillis("other.example"))
    }
    @Test fun successfulFastChaptersOnlySlowWhenProviderActuallyRateLimits(){
        assertEquals(2_500L,NovelHttpPacing.adaptiveGapMillis("freewebnovel.com",0))
        assertEquals(5_000L,NovelHttpPacing.adaptiveGapMillis("freewebnovel.com",1))
        assertEquals(8_000L,NovelHttpPacing.adaptiveGapMillis("freewebnovel.com",2))
        assertEquals(12_000L,NovelHttpPacing.adaptiveGapMillis("freewebnovel.com",3))
        assertEquals(1_750L,NovelHttpPacing.adaptiveGapMillis("novelbuddy.me",20))
    }
    @Test fun repeatedProvider429IncreasesCooldownWithoutHammering(){
        assertEquals(120_000L,NovelHttpPacing.repeatedLimitDelayMillis(1))
        assertEquals(300_000L,NovelHttpPacing.repeatedLimitDelayMillis(2))
        assertEquals(900_000L,NovelHttpPacing.repeatedLimitDelayMillis(3))
        assertEquals(3_600_000L,NovelHttpPacing.repeatedLimitDelayMillis(20))
    }
    @Test fun retryAfterSecondsCannotScheduleTooSoonOrForever(){
        val now=1_700_000_000_000L
        assertEquals(120_000L,NovelHttpPacing.retryDelayMillis(null,now))
        assertEquals(120_000L,NovelHttpPacing.retryDelayMillis("0",now))
        assertEquals(240_000L,NovelHttpPacing.retryDelayMillis("240",now))
        assertEquals(86_400_000L,NovelHttpPacing.retryDelayMillis("999999999999",now))
    }
    @Test fun acceptsHttpDateWithoutDisregardingServerHold(){
        val now=1_700_000_000_000L
        val later=java.time.ZonedDateTime
            .ofInstant(java.time.Instant.ofEpochMilli(now+600_000L),
                java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        assertEquals(600_000L,NovelHttpPacing.retryDelayMillis(later,now))
    }
}
