package com.azrael.pixivdumpsync

import org.junit.Assert.assertEquals
import org.junit.Test

class NovelHttpPacingTest {
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
