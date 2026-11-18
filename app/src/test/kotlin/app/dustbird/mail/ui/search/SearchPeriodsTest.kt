package app.dustbird.mail.ui.search

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchPeriodsTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val now = ZonedDateTime.of(2026, 11, 18, 0, 30, 0, 0, seoul)

    private fun start(year: Int, month: Int, day: Int) = ZonedDateTime.of(year, month, day, 0, 0, 0, 0, seoul).toInstant().toEpochMilli()

    @Test
    fun presetsStartAtLocalMidnightAndStayOpenEnded() {
        assertEquals(start(2026, 11, 18) to null, SearchPeriods.range(SearchPeriod.TODAY, now))
        assertEquals(start(2026, 11, 12) to null, SearchPeriods.range(SearchPeriod.WEEK, now))
        assertEquals(start(2026, 10, 20) to null, SearchPeriods.range(SearchPeriod.MONTH, now))
        assertEquals(start(2026, 1, 1) to null, SearchPeriods.range(SearchPeriod.YEAR, now))
    }

    @Test
    fun anyAndCustomCarryNoPresetRange() {
        assertEquals(null to null, SearchPeriods.range(SearchPeriod.ANY, now))
        assertEquals(null to null, SearchPeriods.range(SearchPeriod.CUSTOM, now))
    }

    @Test
    fun theChosenEndMinuteIsIncluded() {
        assertEquals(start(2026, 11, 19), SearchPeriods.inclusiveEnd(start(2026, 11, 19) - 60_000))
    }
}
