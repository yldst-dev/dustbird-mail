package app.dustbird.mail.ui.mailbox

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import app.dustbird.mail.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MessageTimeFormatter(
    timePattern: String,
    monthDayPattern: String,
    fullPattern: String,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val time = DateTimeFormatter.ofPattern(timePattern, Locale.KOREA)
    private val monthDay = DateTimeFormatter.ofPattern(monthDayPattern, Locale.KOREA)
    private val full = DateTimeFormatter.ofPattern(fullPattern, Locale.KOREA)

    fun format(epochMillis: Long, now: Instant = Instant.now()): String {
        val moment = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val today = now.atZone(zone)
        return when {
            moment.toLocalDate() == today.toLocalDate() -> time.format(moment)
            moment.year == today.year -> monthDay.format(moment)
            else -> full.format(moment)
        }
    }
}

@Composable
fun rememberMessageTimeFormatter(): MessageTimeFormatter {
    val time = stringResource(R.string.mailbox_time_today)
    val monthDay = stringResource(R.string.mailbox_date_this_year)
    val full = stringResource(R.string.mailbox_date_older)
    return remember(time, monthDay, full) { MessageTimeFormatter(time, monthDay, full) }
}
