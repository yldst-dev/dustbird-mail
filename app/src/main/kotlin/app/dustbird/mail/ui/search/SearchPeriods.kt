package app.dustbird.mail.ui.search

import java.time.ZonedDateTime

enum class SearchPeriod { ANY, TODAY, WEEK, MONTH, YEAR, CUSTOM }

object SearchPeriods {
    val presets = listOf(SearchPeriod.TODAY, SearchPeriod.WEEK, SearchPeriod.MONTH, SearchPeriod.YEAR)

    fun range(period: SearchPeriod, now: ZonedDateTime): Pair<Long?, Long?> {
        val today = now.toLocalDate()
        val start = when (period) {
            SearchPeriod.ANY, SearchPeriod.CUSTOM -> return null to null
            SearchPeriod.TODAY -> today
            SearchPeriod.WEEK -> today.minusDays(6)
            SearchPeriod.MONTH -> today.minusDays(29)
            SearchPeriod.YEAR -> today.withDayOfYear(1)
        }
        return start.atStartOfDay(now.zone).toInstant().toEpochMilli() to null
    }

    fun inclusiveEnd(endMinute: Long): Long = endMinute + 60_000
}
